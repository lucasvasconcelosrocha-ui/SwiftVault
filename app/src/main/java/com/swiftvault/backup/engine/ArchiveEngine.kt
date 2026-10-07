package com.swiftvault.backup.engine

import com.google.gson.Gson
import com.swiftvault.backup.data.model.BackupManifest
import com.swiftvault.backup.data.model.BackupPartInfo
import com.swiftvault.backup.data.model.ManifestItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles creation and unpacking of SwiftVault archives (.zip containers with Zip64 and legacy .svb files).
 * Features:
 * - Direct streaming with Zip64 and STORED entries (no recompression of pre-compressed data and tar archives).
 * - Chunked AES-256-GCM per-entry encryption keeping valid ZIP container structure.
 * - Multi-part file splitting (.zip.001, .zip.002) with continuous byte-level SeekableByteChannel.
 * - Seamless backward-compatible read and extraction of legacy SVB1 containers.
 */
object ArchiveEngine {

    val SVB_MAGIC = byteArrayOf(0x53, 0x56, 0x42, 0x01) // "SVB1"
    val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
    private val gson = Gson()
    private const val BUFFER_SIZE = 64 * 1024

    data class PackEntry(
        val entryName: String,
        val sourceFile: File? = null,
        val rawData: ByteArray? = null,
        val manifestItem: ManifestItem,
        val isStoreOnly: Boolean = false
    )

    /**
     * Creates a standard Zip container with Zip64 support, per-entry encryption,
     * and optional byte-level splitting (.zip.001, .zip.002, etc.).
     */
    suspend fun createZipContainer(
        outputFile: File,
        manifest: BackupManifest,
        entries: List<PackEntry>,
        encryptionPassword: String? = null,
        splitSizeBytes: Long = 0L,
        onProgress: suspend (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val tempZipFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tempZipFile.exists()) tempZipFile.delete()

        val totalBytesToPack = entries.sumOf { it.sourceFile?.length() ?: it.rawData?.size?.toLong() ?: 0L }
        var totalBytesWritten = 0L

        // 1. Stream Zip entries using Apache Commons Compress
        ZipArchiveOutputStream(tempZipFile).use { zos ->
            // Enable Zip64 automatically when required
            zos.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded)

            for (entry in entries) {
                val zipEntry = ZipArchiveEntry(entry.entryName)
                zipEntry.time = System.currentTimeMillis()

                val shouldStore = entry.isStoreOnly ||
                        entry.manifestItem.compressionMethod == "STORED" ||
                        entry.entryName.endsWith(".apk", ignoreCase = true) ||
                        entry.entryName.endsWith(".tar", ignoreCase = true) ||
                        entry.entryName.endsWith(".png", ignoreCase = true) ||
                        entry.entryName.endsWith(".jpg", ignoreCase = true) ||
                        entry.entryName.endsWith(".mp4", ignoreCase = true) ||
                        !encryptionPassword.isNullOrEmpty()

                if (shouldStore) {
                    zipEntry.method = ZipEntry.STORED
                } else {
                    zipEntry.method = ZipEntry.DEFLATED
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                }

                zos.putArchiveEntry(zipEntry)

                val entryInputStream: InputStream = if (entry.rawData != null) {
                    ByteArrayInputStream(entry.rawData)
                } else if (entry.sourceFile != null && entry.sourceFile.exists()) {
                    FileInputStream(entry.sourceFile)
                } else {
                    ByteArrayInputStream(ByteArray(0))
                }

                entryInputStream.use { input ->
                    if (!encryptionPassword.isNullOrEmpty()) {
                        SecurityManager.encryptStreamChunked(input, zos, encryptionPassword) { written ->
                            totalBytesWritten += written
                            kotlinx.coroutines.runBlocking { onProgress(totalBytesWritten, totalBytesToPack) }
                        }
                    } else {
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                            totalBytesWritten += read
                            onProgress(totalBytesWritten, totalBytesToPack)
                        }
                    }
                }

                zos.closeArchiveEntry()
            }

            // 2. Write manifest.json at root of zip
            val manifestJson = gson.toJson(manifest)
            val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
            val manifestEntry = ZipArchiveEntry("manifest.json")
            manifestEntry.method = ZipEntry.DEFLATED
            zos.putArchiveEntry(manifestEntry)
            zos.write(manifestBytes)
            zos.closeArchiveEntry()

            zos.finish()
        }

        // 3. Handle splitting into .zip.001, .zip.002 if requested
        if (splitSizeBytes > 0L && tempZipFile.length() > splitSizeBytes) {
            val partInfos = splitFileIntoParts(tempZipFile, outputFile, splitSizeBytes)
            tempZipFile.delete()

            // Update parts in manifest if necessary by reading parts
            return@withContext partInfos.sumOf { it.sizeBytes }
        } else {
            if (outputFile.exists()) outputFile.delete()
            if (!tempZipFile.renameTo(outputFile)) {
                tempZipFile.copyTo(outputFile, overwrite = true)
                tempZipFile.delete()
            }
            return@withContext outputFile.length()
        }
    }

    private fun splitFileIntoParts(
        sourceFile: File,
        baseOutputFile: File,
        partSizeBytes: Long
    ): List<BackupPartInfo> {
        val parts = mutableListOf<BackupPartInfo>()
        val baseName = if (baseOutputFile.name.endsWith(".zip", ignoreCase = true)) {
            baseOutputFile.name
        } else {
            "${baseOutputFile.name}.zip"
        }

        val totalLength = sourceFile.length()
        var currentOffset = 0L
        var partIndex = 1
        val buffer = ByteArray(1024 * 1024) // 1MB buffer

        FileInputStream(sourceFile).use { fis ->
            while (currentOffset < totalLength) {
                val partFileName = String.format("%s.%03d", baseName, partIndex)
                val partFile = File(baseOutputFile.parentFile, partFileName)
                val bytesToReadThisPart = minOf(partSizeBytes, totalLength - currentOffset)
                var bytesWrittenThisPart = 0L

                val digest = java.security.MessageDigest.getInstance("SHA-256")
                FileOutputStream(partFile).use { fos ->
                    while (bytesWrittenThisPart < bytesToReadThisPart) {
                        val toRead = minOf(buffer.size.toLong(), bytesToReadThisPart - bytesWrittenThisPart).toInt()
                        val r = fis.read(buffer, 0, toRead)
                        if (r == -1) break
                        fos.write(buffer, 0, r)
                        digest.update(buffer, 0, r)
                        bytesWrittenThisPart += r
                    }
                }

                val partSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                parts.add(
                    BackupPartInfo(
                        partIndex = partIndex,
                        fileName = partFileName,
                        sizeBytes = partFile.length(),
                        sha256Checksum = partSha256,
                        startByte = currentOffset,
                        endByte = currentOffset + bytesWrittenThisPart - 1
                    )
                )

                currentOffset += bytesWrittenThisPart
                partIndex++
            }
        }
        return parts
    }

    /**
     * Resolves all part files for a given archive file if split (.zip.001, etc.) or returns single file.
     */
    fun resolveArchiveFiles(primaryFile: File): List<File> {
        val name = primaryFile.name
        val parent = primaryFile.parentFile ?: return listOf(primaryFile)

        if (name.matches(Regex(".*\\.zip\\.\\d{3}$", RegexOption.IGNORE_CASE))) {
            val baseName = name.substringBeforeLast(".zip.") + ".zip."
            val parts = parent.listFiles { f -> f.name.startsWith(baseName, ignoreCase = true) }
            if (!parts.isNullOrEmpty()) {
                return parts.sortedBy { it.name }
            }
        }

        // Check if there are split parts for this .zip
        val potentialParts = parent.listFiles { f -> f.name.startsWith("${name}.", ignoreCase = true) && f.name.matches(Regex(".*\\.zip\\.\\d{3}$", RegexOption.IGNORE_CASE)) }
        if (!potentialParts.isNullOrEmpty()) {
            return potentialParts.sortedBy { it.name }
        }

        return listOf(primaryFile)
    }

    /**
     * Reads BackupManifest from either a modern .zip archive (single or split) or legacy .svb container.
     */
    suspend fun readManifest(containerFile: File): BackupManifest = withContext(Dispatchers.IO) {
        val files = resolveArchiveFiles(containerFile)
        val firstFile = files.first()

        if (!firstFile.exists()) {
            throw FileNotFoundException("Arquivo de container não encontrado: ${firstFile.absolutePath}")
        }

        // Inspect first 4 bytes
        val magic = ByteArray(4)
        FileInputStream(firstFile).use { fis ->
            val read = fis.read(magic)
            if (read < 4) throw IllegalArgumentException("Arquivo vazio ou corrompido.")
        }

        // 1. Legacy SVB1 Format or .svb files
        if (magic.contentEquals(SVB_MAGIC)) {
            return@withContext readLegacySvbManifest(firstFile)
        } else if (firstFile.name.endsWith(".svb", ignoreCase = true)) {
            throw IllegalArgumentException("Arquivo inválido: cabeçalho SwiftVault (.svb) não reconhecido.")
        }

        // 2. Modern Zip Container (Single or Split)
        try {
            MultiPartSeekableByteChannel(files).use { channel ->
                ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                    val manifestEntry = zipFile.getEntry("manifest.json")
                        ?: throw IllegalArgumentException("Arquivo inválido: 'manifest.json' não encontrado no container.")

                    val jsonContent = zipFile.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return@withContext gson.fromJson(jsonContent, BackupManifest::class.java)
                }
            }
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Arquivo inválido ou corrompido: ${e.message}", e)
        }
    }

    private fun readLegacySvbManifest(containerFile: File): BackupManifest {
        FileInputStream(containerFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                val magic = ByteArray(4)
                val readMagic = bis.read(magic)
                if (readMagic != 4 || !magic.contentEquals(SVB_MAGIC)) {
                    throw IllegalArgumentException("Arquivo inválido: cabeçalho SwiftVault (.svb) não reconhecido.")
                }

                val lenBytes = ByteArray(4)
                bis.read(lenBytes)
                val manifestLen = ByteBuffer.wrap(lenBytes).int

                if (manifestLen <= 0 || manifestLen > 10 * 1024 * 1024) {
                    throw IllegalArgumentException("Manifest corrompido ou de tamanho inválido.")
                }

                val manifestBytes = ByteArray(manifestLen)
                var totalRead = 0
                while (totalRead < manifestLen) {
                    val r = bis.read(manifestBytes, totalRead, manifestLen - totalRead)
                    if (r == -1) break
                    totalRead += r
                }

                val manifestJson = String(manifestBytes, Charsets.UTF_8)
                return gson.fromJson(manifestJson, BackupManifest::class.java)
            }
        }
    }

    /**
     * Unpacks selected entries from modern .zip or legacy .svb container.
     */
    suspend fun unpackContainer(
        containerFile: File,
        targetOutputDir: File,
        manifest: BackupManifest? = null,
        selectedArchivePaths: Set<String>? = null,
        decryptionPassword: String? = null,
        onProgress: suspend (bytesRead: Long, totalEstimatedBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        targetOutputDir.mkdirs()
        val files = resolveArchiveFiles(containerFile)
        val firstFile = files.first()

        val magic = ByteArray(4)
        FileInputStream(firstFile).use { it.read(magic) }

        if (magic.contentEquals(SVB_MAGIC)) {
            // Legacy SVB1 Unpack
            unpackLegacySvb(firstFile, targetOutputDir, manifest, selectedArchivePaths, decryptionPassword, onProgress)
            return@withContext
        }

        // Modern Zip Container Unpack
        val activeManifest = manifest ?: readManifest(containerFile)
        val totalSize = activeManifest.totalSizeBytes
        var bytesRestored = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        MultiPartSeekableByteChannel(files).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "manifest.json" || entry.isDirectory) continue

                    if (selectedArchivePaths == null || selectedArchivePaths.contains(entry.name)) {
                        val outFile = File(targetOutputDir, entry.name)
                        // Anti-Path-Traversal check
                        val destCanonical = targetOutputDir.canonicalPath
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(destCanonical + File.separator) && outCanonical != destCanonical) {
                            throw SecurityException("Tentativa de Path Traversal bloqueada: ${entry.name}")
                        }

                        outFile.parentFile?.mkdirs()

                        zipFile.getInputStream(entry).use { rawIn ->
                            BufferedInputStream(rawIn, BUFFER_SIZE).use { bis ->
                                FileOutputStream(outFile).use { fos ->
                                    if (SecurityManager.isChunkedEncryptedStream(bis)) {
                                        if (decryptionPassword.isNullOrEmpty()) {
                                            throw IllegalArgumentException("A entrada '${entry.name}' está criptografada e requer a senha.")
                                        }
                                        SecurityManager.decryptStreamChunked(bis, fos, decryptionPassword) { written ->
                                            bytesRestored += written
                                            kotlinx.coroutines.runBlocking { onProgress(bytesRestored, totalSize) }
                                        }
                                    } else {
                                        var read: Int
                                        while (bis.read(buffer).also { read = it } != -1) {
                                            fos.write(buffer, 0, read)
                                            bytesRestored += read
                                            onProgress(bytesRestored, totalSize)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun unpackLegacySvb(
        containerFile: File,
        targetOutputDir: File,
        manifest: BackupManifest?,
        selectedArchivePaths: Set<String>?,
        decryptionPassword: String?,
        onProgress: suspend (bytesRead: Long, totalEstimatedBytes: Long) -> Unit
    ) {
        FileInputStream(containerFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                // Skip Magic & Manifest
                val magic = ByteArray(4)
                bis.read(magic)
                val lenBytes = ByteArray(4)
                bis.read(lenBytes)
                val manifestLen = ByteBuffer.wrap(lenBytes).int
                bis.skip(manifestLen.toLong())

                val activeManifest = manifest ?: readLegacySvbManifest(containerFile)

                val payloadStream: InputStream = if (activeManifest.isEncrypted) {
                    SecurityManager.createDecryptingInputStream(bis, decryptionPassword)
                } else {
                    bis
                }

                val totalSize = activeManifest.totalSizeBytes
                var bytesRestored = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                ZipInputStream(BufferedInputStream(payloadStream, BUFFER_SIZE)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (selectedArchivePaths == null || selectedArchivePaths.contains(entryName)) {
                            val outFile = File(targetOutputDir, entryName)
                            val destCanonical = targetOutputDir.canonicalPath
                            val outCanonical = outFile.canonicalPath
                            if (!outCanonical.startsWith(destCanonical + File.separator) && outCanonical != destCanonical) {
                                throw SecurityException("Tentativa de Path Traversal bloqueada: $entryName")
                            }

                            outFile.parentFile?.mkdirs()

                            FileOutputStream(outFile).use { fos ->
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                    bytesRestored += read
                                    onProgress(bytesRestored, totalSize)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }

    /**
     * Legacy SVB1 container creator preserved for backward-compatibility unit testing.
     */
    suspend fun createSvbContainer(
        outputFile: File,
        manifest: BackupManifest,
        entries: List<PackEntry>,
        encryptionPassword: String? = null,
        onProgress: suspend (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val totalBytesToPack = entries.sumOf { it.sourceFile?.length() ?: it.rawData?.size?.toLong() ?: 0L }
        var totalBytesWritten = 0L

        FileOutputStream(outputFile).use { fos ->
            BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                bos.write(SVB_MAGIC)

                val manifestJson = gson.toJson(manifest)
                val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
                val lengthBuffer = ByteBuffer.allocate(4).putInt(manifestBytes.size).array()
                bos.write(lengthBuffer)
                bos.write(manifestBytes)
                bos.flush()

                val targetStream: OutputStream = if (manifest.isEncrypted) {
                    val (cipherStream, _) = SecurityManager.createEncryptingOutputStream(bos, encryptionPassword)
                    cipherStream
                } else {
                    bos
                }

                ZipOutputStream(BufferedOutputStream(targetStream, BUFFER_SIZE)).use { zos ->
                    zos.setLevel(5)
                    val buffer = ByteArray(BUFFER_SIZE)

                    for (entry in entries) {
                        val zipEntry = ZipEntry(entry.entryName)
                        zipEntry.time = System.currentTimeMillis()
                        zos.putNextEntry(zipEntry)

                        if (entry.rawData != null) {
                            zos.write(entry.rawData)
                            totalBytesWritten += entry.rawData.size
                            onProgress(totalBytesWritten, totalBytesToPack)
                        } else if (entry.sourceFile != null && entry.sourceFile.exists()) {
                            if (entry.sourceFile.isFile) {
                                FileInputStream(entry.sourceFile).use { fis ->
                                    var read: Int
                                    while (fis.read(buffer).also { read = it } != -1) {
                                        zos.write(buffer, 0, read)
                                        totalBytesWritten += read
                                        onProgress(totalBytesWritten, totalBytesToPack)
                                    }
                                }
                            }
                        }
                        zos.closeEntry()
                    }
                    zos.finish()
                    zos.flush()
                }
            }
        }

        return@withContext outputFile.length()
    }
}

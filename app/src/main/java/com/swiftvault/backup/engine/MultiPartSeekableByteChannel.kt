package com.swiftvault.backup.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * Provides a continuous SeekableByteChannel over a sequence of split archive files (.zip.001, .zip.002, etc.)
 * or a single file, allowing Apache Commons Compress ZipFile to parse Central Directory records
 * and seek directly into entries without concatenating files on disk.
 */
class MultiPartSeekableByteChannel(val files: List<File>) : SeekableByteChannel {

    private var open = true
    private var globalPosition = 0L
    private val totalSize: Long = files.sumOf { it.length() }
    private val fileOffsets: LongArray
    private var currentFileIndex = -1
    private var currentRaf: RandomAccessFile? = null

    init {
        require(files.isNotEmpty()) { "MultiPartSeekableByteChannel requer pelo menos um arquivo." }
        fileOffsets = LongArray(files.size)
        var acc = 0L
        for (i in files.indices) {
            fileOffsets[i] = acc
            acc += files[i].length()
        }
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        if (open) {
            open = false
            currentRaf?.close()
            currentRaf = null
            currentFileIndex = -1
        }
    }

    override fun size(): Long = totalSize

    override fun position(): Long = globalPosition

    override fun position(newPosition: Long): SeekableByteChannel {
        if (!open) throw ClosedChannelException()
        require(newPosition >= 0) { "Posição não pode ser negativa: $newPosition" }
        globalPosition = newPosition
        return this
    }

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (globalPosition >= totalSize) return -1
        if (!dst.hasRemaining()) return 0

        val fileIdx = findFileIndex(globalPosition)
        if (fileIdx != currentFileIndex) {
            currentRaf?.close()
            currentRaf = RandomAccessFile(files[fileIdx], "r")
            currentFileIndex = fileIdx
        }

        val raf = currentRaf ?: throw IllegalStateException("RandomAccessFile não inicializado.")
        val localOffset = globalPosition - fileOffsets[fileIdx]
        raf.seek(localOffset)

        val remainingInPart = files[fileIdx].length() - localOffset
        val bytesToRead = minOf(dst.remaining().toLong(), remainingInPart).toInt()

        val buf = ByteArray(bytesToRead)
        val read = raf.read(buf, 0, bytesToRead)
        if (read > 0) {
            dst.put(buf, 0, read)
            globalPosition += read
            return read
        }
        return -1
    }

    private fun findFileIndex(pos: Long): Int {
        for (i in files.indices) {
            val start = fileOffsets[i]
            val end = start + files[i].length()
            if (pos in start until end) return i
        }
        return files.size - 1
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
}

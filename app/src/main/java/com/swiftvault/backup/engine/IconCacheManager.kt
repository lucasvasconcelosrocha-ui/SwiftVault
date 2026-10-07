package com.swiftvault.backup.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages extraction and caching of real application icons from PackageManager.
 * Full support for AdaptiveIconDrawable, VectorDrawable, and standard Drawables.
 * Prevents redundant GC allocations and UI lag in LazyLists.
 */
class IconCacheManager private constructor(private val context: Context) {

    // 40MB memory cache for ImageBitmaps
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    companion object {
        @Volatile
        private var instance: IconCacheManager? = null

        fun getInstance(context: Context): IconCacheManager {
            return instance ?: synchronized(this) {
                instance ?: IconCacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Retrieve real app icon from cache or PackageManager.
     */
    suspend fun getAppIcon(packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(packageName)?.let { return@withContext it }

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)

            val bitmap = drawableToBitmap(drawable)
            val imageBitmap = bitmap.asImageBitmap()

            memoryCache.put(packageName, imageBitmap)
            return@withContext imageBitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts any Android Drawable (including AdaptiveIconDrawable and VectorDrawable)
     * cleanly into a hardware-independent ARGB_8888 Bitmap.
     */
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val targetWidth = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceIn(96, 192) else 144
        val targetHeight = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceIn(96, 192) else 144

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        } else {
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }

        return bitmap
    }

    /**
     * Clear icon cache
     */
    fun clearCache() {
        memoryCache.evictAll()
    }
}

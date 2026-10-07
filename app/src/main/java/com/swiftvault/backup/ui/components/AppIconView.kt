package com.swiftvault.backup.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swiftvault.backup.engine.IconCacheManager
import com.swiftvault.backup.ui.theme.DarkSurfaceVariant

/**
 * Universal real app icon composable.
 * Uses IconCacheManager and native Android PackageManager.
 * Guarantees that the device's real application icon is always displayed.
 */
@Composable
fun RealAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    shapeRadius: Dp = 10.dp
) {
    val context = LocalContext.current
    val iconCache = IconCacheManager.getInstance(context)

    val iconState = produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = iconCache.getAppIcon(packageName)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(shapeRadius))
            .background(DarkSurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = iconState.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(size)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.45f),
                strokeWidth = 2.dp,
                color = Color.White.copy(alpha = 0.25f)
            )
        }
    }
}

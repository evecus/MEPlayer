package com.tv.meplayer.ui.widget

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

/**
 * 视频缩略图加载组件。
 *
 * 使用 Glide 的 `load(filePath)` 直接加载本地视频文件，Glide 内部会通过
 * [android.media.MediaMetadataRetriever] 提取视频帧并自动缓存到本地存储
 * （DiskCacheStrategy.AUTOMATIC），不压缩原始分辨率。
 *
 * Glide 的视频帧提取是异步的，加载完成前先显示 [Movie] 图标占位。
 *
 * @param videoPath 视频文件的绝对路径
 * @param modifier 布局修饰符（通常指定固定尺寸）
 */
@Composable
fun GlideVideoThumbnail(
    videoPath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant

    // RequestOptions 记住一份即可，避免每次重组都新建
    val options = remember {
        RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .placeholder(ColorDrawable(Color.TRANSPARENT))
            .error(ColorDrawable(Color.TRANSPARENT))
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 占位图标：Glide 加载完成前显示 Movie 图标
        Icon(
            imageVector = Icons.Filled.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxSize(0.7f)
        )

        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    // 初始透明，Glide 加载完成后由 Glide 设置图片
                    setImageDrawable(ColorDrawable(Color.TRANSPARENT))
                }
            },
            update = { imageView ->
                Glide.with(context)
                    .load(videoPath)
                    .apply(options)
                    .into(imageView)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

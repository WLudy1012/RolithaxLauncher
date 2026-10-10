package com.wludy.rolithax.launcher.ui.theme

import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 统一卡片圆角。 */
val GlassCardShape = RoundedCornerShape(20.dp)

/** 玻璃面板不透明度；由 [GlassHost] 按用户设置提供。 */
val LocalGlassAlpha = staticCompositionLocalOf { DEFAULT_GLASS_ALPHA }

/** 当前可供面板模糊的背景层；为 null 时面板只绘制半透明表面色。 */
val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

@Immutable
data class GlassBackdrop(
    internal val hazeState: HazeState,
    /** 注册模糊源所在的窗口 View；弹窗位于独立窗口，坐标不可复用，需据此降级。 */
    internal val ownerView: View,
    val hasImage: Boolean,
)

/** 设备是否支持实时背景模糊（Haze 在 Android 12L 以下降级为半透明着色）。 */
fun isRealtimeBlurSupported(): Boolean = HazeDefaults.blurEnabled()

/**
 * 背景层宿主：绘制背景（自定义图片或主题色柔光），并把它注册为 Haze 模糊源，
 * [content] 中的 [GlassSurface] / [glass] 面板会实时模糊其后方的这层背景。
 * 页面 Scaffold 需保持透明才能透出背景。
 */
@Composable
fun GlassHost(
    config: ThemeConfig,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalRolithaxDarkTheme.current
    val path = if (dark) config.darkBackground else config.lightBackground
    val bitmap by rememberBackdropBitmap(path)
    val hazeState = remember { HazeState() }
    val hasImage = bitmap != null
    val ownerView = LocalView.current
    val backdrop = remember(hazeState, hasImage, ownerView) {
        GlassBackdrop(hazeState, ownerView, hasImage)
    }

    Box(modifier) {
        BackdropLayer(
            bitmap = bitmap,
            dark = dark,
            modifier = Modifier
                .matchParentSize()
                .haze(hazeState),
        )
        CompositionLocalProvider(
            LocalGlassAlpha provides config.glassAlpha,
            LocalGlassBackdrop provides backdrop,
        ) {
            content()
        }
    }
}

@Composable
private fun BackdropLayer(bitmap: ImageBitmap?, dark: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(scheme.background)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 压一层底色，避免高亮图片吞掉面板外的标题与分区文字。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (dark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.18f),
                    ),
            )
        } else {
            val primary = scheme.primary.copy(alpha = if (dark) 0.20f else 0.18f)
            val tertiary = scheme.tertiary.copy(alpha = if (dark) 0.12f else 0.12f)
            Canvas(Modifier.fillMaxSize()) {
                val reach = size.maxDimension
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(primary, Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 0.02f),
                        radius = reach * 0.62f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tertiary, Color.Transparent),
                        center = Offset(size.width * 0.96f, size.height * 0.78f),
                        radius = reach * 0.55f,
                    ),
                )
            }
        }
    }
}

/** 解析后的玻璃外观；由 [rememberGlassStyle] 生成，交给 [glass] 绘制。 */
@Immutable
class GlassStyle internal constructor(
    internal val hazeState: HazeState?,
    internal val hazeStyle: HazeStyle,
    val fill: Color,
    internal val border: Brush,
) {
    val isBlurring: Boolean get() = hazeState != null
}

/**
 * 计算面板外观：有背景图且设备支持时走实时模糊，否则退化为半透明表面色。
 * 不透明度下限按场景收紧，保证无模糊时正文仍有足够对比度。
 */
@Composable
fun rememberGlassStyle(
    tint: Color = Color.Unspecified,
    alpha: Float = LocalGlassAlpha.current,
): GlassStyle {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalRolithaxDarkTheme.current
    val backdrop = LocalGlassBackdrop.current
    val base = if (tint.isSpecified) tint else scheme.surface
    // 弹窗等独立窗口的坐标与模糊源不一致，只做半透明填充，避免画出错位的模糊内容。
    val sameWindow = backdrop != null && backdrop.ownerView === LocalView.current
    val blur = backdrop != null && sameWindow && backdrop.hasImage &&
        alpha < 0.995f && isRealtimeBlurSupported()
    val minAlpha = when {
        blur -> if (dark) 0.42f else 0.32f
        backdrop?.hasImage == true -> 0.86f
        else -> 0.55f
    }
    val effective = alpha.coerceIn(minAlpha, 1f)
    val highlight = Color.White.copy(alpha = if (dark) 0.12f else 0.55f)
    val edge = scheme.outlineVariant.copy(alpha = if (dark) 0.45f else 0.6f)
    return remember(base, effective, blur, dark, backdrop, scheme.background, highlight, edge) {
        GlassStyle(
            hazeState = if (blur) backdrop?.hazeState else null,
            hazeStyle = HazeStyle(
                backgroundColor = scheme.background,
                tints = listOf(HazeTint(base.copy(alpha = effective))),
                blurRadius = 28.dp,
                noiseFactor = 0.06f,
                fallbackTint = HazeTint(base.copy(alpha = max(effective, 0.9f))),
            ),
            fill = base.copy(alpha = effective),
            border = Brush.verticalGradient(listOf(highlight, edge)),
        )
    }
}

/** 按 [style] 绘制玻璃底：裁剪、模糊或半透明填充、顶部高光描边。 */
fun Modifier.glass(style: GlassStyle, shape: Shape = GlassCardShape): Modifier {
    val clipped = clip(shape)
    val filled = style.hazeState?.let { clipped.hazeChild(it, style.hazeStyle) }
        ?: clipped.background(style.fill)
    return filled.border(1.dp, style.border, shape)
}

/** 通用玻璃面板。面板外的页面容器应保持透明，才能透出 [GlassHost] 的背景。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassCardShape,
    tint: Color = Color.Unspecified,
    alpha: Float = LocalGlassAlpha.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = rememberGlassStyle(tint = tint, alpha = alpha)
    val contentColor = if (tint.isSpecified) {
        MaterialTheme.colorScheme.contentColorFor(tint).takeIf { it.isSpecified } ?: LocalContentColor.current
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(modifier = modifier.glass(style, shape), content = content)
    }
}

// 当前 Compose/Lint 组合误报：下方 producer 已在加载完成后明确写入 value。
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberBackdropBitmap(path: String) = produceState<ImageBitmap?>(initialValue = null, path) {
    value = if (path.isBlank()) null else withContext(Dispatchers.IO) { loadBackdropBitmap(path) }
}

private const val BACKDROP_MAX_SIDE = 1600

private fun loadBackdropBitmap(path: String): ImageBitmap? = runCatching {
    if (!File(path).exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    var longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= BACKDROP_MAX_SIDE) {
        longest /= 2
        sample *= 2
    }
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

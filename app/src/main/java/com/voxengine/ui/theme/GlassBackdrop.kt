package com.voxengine.ui.theme

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Record only the scene, never the glass dock itself, to avoid a recursive layer. */
fun Modifier.recordGlassBackdrop(backdrop: GlassBackdrop): Modifier =
    onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

@Composable
fun GlassScene(modifier: Modifier = Modifier) {
    val dark = LocalGlassDarkTheme.current
    val base = MaterialTheme.colorScheme.background
    val mint = if (dark) Color(0xFF305E50) else Color(0xFFB9E5D4)
    val sand = if (dark) Color(0xFF4C4734) else Color(0xFFEBD7B8)
    val blue = if (dark) Color(0xFF213F50) else Color(0xFFCDDFEA)
    Canvas(modifier) {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                listOf(mint.copy(alpha = 0.72f), Color.Transparent),
                center = Offset(size.width * 0.96f, size.height * 0.12f),
                radius = size.width * 0.95f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(sand.copy(alpha = 0.60f), Color.Transparent),
                center = Offset(0f, size.height * 0.58f),
                radius = size.width * 0.86f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(blue.copy(alpha = 0.70f), Color.Transparent),
                center = Offset(size.width * 0.86f, size.height),
                radius = size.width * 0.9f
            )
        )
    }
}

/** The effect is on a separate backing layer, so labels and icons stay sharp. */
@Composable
fun Modifier.glassBackdrop(
    backdrop: GlassBackdrop,
    shape: Shape
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawWithCache {
            val backing = obtainGraphicsLayer()
            backing.renderEffect = when {
                Build.VERSION.SDK_INT >= 33 -> runCatching {
                    createLensEffect(size.width, size.height, 28.dp.toPx(), 10.dp.toPx())
                }.getOrElse {
                    RenderEffect.createBlurEffect(10.dp.toPx(), 10.dp.toPx(), Shader.TileMode.CLAMP)
                }.asComposeRenderEffect()
                Build.VERSION.SDK_INT >= 31 -> RenderEffect.createBlurEffect(
                    10.dp.toPx(), 10.dp.toPx(), Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
                else -> null
            }
            onDrawWithContent {
                backing.record {
                    val offset = backdrop.origin - origin
                    translate(offset.x, offset.y) { drawLayer(backdrop.layer) }
                }
                drawLayer(backing)
                drawContent()
            }
        }
}

@RequiresApi(33)
private fun createLensEffect(width: Float, height: Float, radius: Float, blur: Float): RenderEffect {
    val shader = RuntimeShader(LENS_SHADER).apply {
        setFloatUniform("resolution", width, height)
        setFloatUniform("radius", radius.coerceAtMost(height / 2f))
        setFloatUniform("rim", blur * 1.4f)
    }
    return RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(shader, "scene"),
        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
    )
}

// Small inward displacement at the rounded rim, not a distortion of foreground text.
private const val LENS_SHADER = """
uniform shader scene;
uniform float2 resolution;
uniform float radius;
uniform float rim;
half4 main(float2 point) {
    float2 halfSize = resolution * 0.5;
    float2 relative = point - halfSize;
    float2 q = abs(relative) - (halfSize - float2(radius));
    float distance = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float edge = 1.0 - smoothstep(0.0, rim, -distance);
    float2 normal = normalize(relative / max(halfSize, float2(1.0)) + float2(0.0001));
    float2 samplePoint = clamp(point - normal * edge * edge * rim * 0.32,
                              float2(0.0), resolution);
    half4 color = scene.eval(samplePoint);
    color.r = scene.eval(samplePoint + normal * edge * 0.65).r;
    color.b = scene.eval(samplePoint - normal * edge * 0.65).b;
    return color;
}
"""

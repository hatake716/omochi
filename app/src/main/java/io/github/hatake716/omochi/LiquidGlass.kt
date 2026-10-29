package io.github.hatake716.omochi

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Light-appearance approximation of iOS 26/27 Liquid Glass.
 *
 * Controls float above the content layer as translucent glass. Real-time backdrop
 * blur (RenderEffect) over the WebView is deliberately not used: it forces an
 * offscreen copy of the workbench every frame. Instead four layers are stacked in
 * the draw phase only:
 *
 * 1. Fill: translucent white (or a tint) whose top is brightened by
 * 2. Diffusion: light that fades out from the top edge (merged into the fill brush)
 * 3. Dark edge: the thin separation line iOS 27 added around glass
 * 4. Specular rim: an inner highlight that is brightest top-left and bottom-right
 *
 * Paths, outlines and brushes are cached with a CacheDrawModifierNode and are only
 * rebuilt when the size or an argument changes. Nothing here reads snapshot state
 * during composition.
 */

/** Material of a Liquid Glass surface. */
enum class GlassTone {
    /** Toolbars, floating buttons and cards over content (about 78% white). */
    Regular,

    /** Sheets, menus and banners that must stay readable over busy content (about 90% white). */
    Thick,

    /** Prominent or active controls: the tint at about 88%, readable with white glyphs. */
    Tinted,
}

/** iOS capsule. Use it for buttons, tab bars and banners. */
val CapsuleShape: Shape = RoundedCornerShape(50)

/**
 * Static, very light gradient for dashboard-style backgrounds. It never animates, so a
 * screen using it costs one shader per size change.
 */
val AmbientBackground: Brush = Brush.verticalGradient(
    0f to Color(0xFFEAF1FF),
    0.5f to Color(0xFFF4F1FF),
    1f to Color(0xFFFFF4EC),
)

/** Spring used for positional motion (switch thumbs, segment knobs, sheets). */
fun <T> iosSpring(visibilityThreshold: T? = null): SpringSpec<T> = spring(
    dampingRatio = IOS_SPRING_DAMPING,
    stiffness = IOS_SPRING_STIFFNESS,
    visibilityThreshold = visibilityThreshold,
)

private const val IOS_SPRING_DAMPING = 0.86f
private const val IOS_SPRING_STIFFNESS = 450f

// ---------------------------------------------------------------------------
// Glass material

/**
 * Draws a Liquid Glass surface behind the content.
 *
 * - Draw only: no layout, no shadow (combine with [glassShadow]) and no clipping
 *   (add `clip(shape)` when the content must not overflow the shape).
 * - [tint] is used only by [GlassTone.Tinted]. It is deepened if needed so that white
 *   text on the glass keeps a 4.5:1 contrast over a white backdrop.
 * - [highlight] scales the specular rim; lower it for small controls.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tone: GlassTone = GlassTone.Regular,
    tint: Color = IosColors.Blue,
    highlight: Float = 1f,
): Modifier = this then LiquidGlassElement(shape, tone, tint, highlight)

private data class LiquidGlassElement(
    val shape: Shape,
    val tone: GlassTone,
    val tint: Color,
    val highlight: Float,
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create(): LiquidGlassNode = LiquidGlassNode(shape, tone, tint, highlight)

    override fun update(node: LiquidGlassNode) {
        node.update(shape, tone, tint, highlight)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlass"
        properties["shape"] = shape
        properties["tone"] = tone
        properties["tint"] = tint
        properties["highlight"] = highlight
    }
}

private class LiquidGlassNode(
    private var shape: Shape,
    private var tone: GlassTone,
    private var tint: Color,
    private var highlight: Float,
) : DelegatingNode() {
    // Invalidation is explicit: the cache is rebuilt only when an argument really changed.
    override val shouldAutoInvalidate: Boolean = false

    private val cache = delegate(CacheDrawModifierNode { buildGlass() })

    fun update(shape: Shape, tone: GlassTone, tint: Color, highlight: Float) {
        if (this.shape == shape && this.tone == tone && this.tint == tint && this.highlight == highlight) {
            return
        }
        this.shape = shape
        this.tone = tone
        this.tint = tint
        this.highlight = highlight
        cache.invalidateDrawCache()
    }

    private fun CacheDrawScope.buildGlass(): DrawResult {
        val colors = glassColors(tone, tint, highlight)
        val edgePx = GLASS_EDGE_WIDTH.toPx()
        val rimPx = GLASS_RIM_WIDTH.toPx()

        val outline = shape.createOutline(size, layoutDirection, this)
        // Both strokes stay inside the bounds so a clipping parent never cuts them.
        val edgeInset = edgePx / 2f
        val edgeOutline = shape.createOutline(size.inset(edgeInset), layoutDirection, this)
        val rimInset = edgePx + rimPx / 2f
        val rimOutline = shape.createOutline(size.inset(rimInset), layoutDirection, this)

        // Fill and top diffusion are one brush: one draw call instead of two.
        val body = Brush.verticalGradient(
            0f to colors.top,
            colors.diffusionEnd to colors.fill,
            startY = 0f,
            endY = size.height,
        )
        val specular = Brush.linearGradient(
            0f to Color.White.copy(alpha = colors.specularStrong),
            0.28f to Color.White.copy(alpha = colors.specularSoft),
            0.72f to Color.White.copy(alpha = colors.specularSoft * 0.6f),
            1f to Color.White.copy(alpha = colors.specularReflect),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val edgeStroke = Stroke(width = edgePx)
        val rimStroke = Stroke(width = rimPx)
        val drawSpecular = colors.specularStrong > 0f && rimOutline.bounds.width > 0f

        return onDrawBehind {
            drawOutline(outline, body)
            translate(edgeInset, edgeInset) {
                drawOutline(edgeOutline, colors.edge, style = edgeStroke)
            }
            if (drawSpecular) {
                translate(rimInset, rimInset) {
                    drawOutline(rimOutline, specular, style = rimStroke)
                }
            }
        }
    }
}

private fun Size.inset(amount: Float): Size = Size(
    width = (width - amount * 2f).coerceAtLeast(0f),
    height = (height - amount * 2f).coerceAtLeast(0f),
)

@Immutable
internal data class GlassColors(
    /** Fill color at the bottom of the diffusion. */
    val fill: Color,
    /** Fill with the top diffusion light composited over it. */
    val top: Color,
    /** Fraction of the height where the diffusion has faded out. */
    val diffusionEnd: Float,
    val edge: Color,
    val specularStrong: Float,
    val specularSoft: Float,
    val specularReflect: Float,
)

internal fun glassColors(tone: GlassTone, tint: Color, highlight: Float): GlassColors {
    val h = highlight.coerceIn(0f, 1.5f)
    return when (tone) {
        GlassTone.Regular -> glassColors(
            fill = Color.White.copy(alpha = REGULAR_GLASS_ALPHA),
            diffusion = 0.45f,
            diffusionEnd = 0.55f,
            edge = Color.Black.copy(alpha = 0.10f),
            specularStrong = 0.95f * h,
            specularSoft = 0.22f * h,
            specularReflect = 0.55f * h,
        )

        GlassTone.Thick -> glassColors(
            fill = Color.White.copy(alpha = THICK_GLASS_ALPHA),
            diffusion = 0.40f,
            diffusionEnd = 0.55f,
            edge = Color.Black.copy(alpha = 0.08f),
            specularStrong = 0.80f * h,
            specularSoft = 0.18f * h,
            specularReflect = 0.45f * h,
        )

        // Short, faint diffusion so the glyph area keeps the contrast computed for the fill.
        GlassTone.Tinted -> glassColors(
            fill = readableTintedGlassFill(tint),
            diffusion = 0.12f,
            diffusionEnd = 0.45f,
            edge = Color.Black.copy(alpha = 0.12f),
            specularStrong = 0.55f * h,
            specularSoft = 0.12f * h,
            specularReflect = 0.35f * h,
        )
    }
}

private fun glassColors(
    fill: Color,
    diffusion: Float,
    diffusionEnd: Float,
    edge: Color,
    specularStrong: Float,
    specularSoft: Float,
    specularReflect: Float,
): GlassColors = GlassColors(
    fill = fill,
    top = Color.White.copy(alpha = diffusion).compositeOver(fill),
    diffusionEnd = diffusionEnd,
    edge = edge,
    specularStrong = specularStrong,
    specularSoft = specularSoft,
    specularReflect = specularReflect,
)

/**
 * Fill of [GlassTone.Tinted] glass: [tint] at [alpha], deepened toward black in small
 * steps until white text has at least [minContrast] against the fill composited over
 * white (the lightest backdrop Omochi has). System blue ends up slightly deeper, close
 * to its pressed shade.
 */
internal fun readableTintedGlassFill(
    tint: Color,
    alpha: Float = TINTED_GLASS_ALPHA,
    minContrast: Float = 4.5f,
): Color {
    val opaque = tint.copy(alpha = 1f)
    for (step in 0..TINT_SHADE_STEPS) {
        val shade = lerp(opaque, Color.Black, step * TINT_SHADE_STEP)
        val candidate = shade.copy(alpha = alpha)
        if (contrastRatio(Color.White, candidate.compositeOver(Color.White)) >= minContrast) {
            return candidate
        }
    }
    return Color.Black.copy(alpha = alpha)
}

/** WCAG contrast ratio of two opaque colors (1..21). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

private const val REGULAR_GLASS_ALPHA = 0.78f
private const val THICK_GLASS_ALPHA = 0.90f
internal const val TINTED_GLASS_ALPHA = 0.88f
private const val TINT_SHADE_STEP = 0.025f
private const val TINT_SHADE_STEPS = 40
private val GLASS_EDGE_WIDTH = 1.dp
private val GLASS_RIM_WIDTH = 1.dp

// ---------------------------------------------------------------------------
// Shadow

/**
 * Soft, low-alpha shadow for floating glass. Elevation shadows are drawn by HWUI from
 * the outline, so a [ContinuousRoundedShape] is swapped for [continuousShadowShape]: a
 * rounded rectangle gets the analytic shadow instead of re-tessellating a path.
 * Place it before [liquidGlass] in the chain; it never clips.
 */
fun Modifier.glassShadow(shape: Shape, elevation: Dp = 12.dp): Modifier = shadow(
    elevation = elevation,
    shape = glassShadowShape(shape),
    clip = false,
    ambientColor = GLASS_SHADOW_AMBIENT,
    spotColor = GLASS_SHADOW_SPOT,
)

internal fun glassShadowShape(shape: Shape): Shape =
    if (shape is ContinuousRoundedShape) continuousShadowShape(shape.radius) else shape

// The theme multiplies these by its ambient/spot shadow alpha, so they stay faint.
private val GLASS_SHADOW_AMBIENT = Color.Black.copy(alpha = 0.55f)
private val GLASS_SHADOW_SPOT = Color.Black.copy(alpha = 0.45f)

// ---------------------------------------------------------------------------
// Press feedback

/**
 * iOS press feedback: while [interactionSource] reports a press, the element shrinks
 * to [pressedScale] and fades to [pressedAlpha], then springs back on release.
 *
 * It does not add a click handler; pass the same [interactionSource] to `clickable`
 * (usually with `indication = null`). The animated value is read only inside the
 * graphics layer block, so pressing never recomposes the caller.
 */
fun Modifier.iosPressable(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    pressedAlpha: Float = 0.85f,
): Modifier = this then IosPressableElement(interactionSource, pressedScale, pressedAlpha)

private data class IosPressableElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
    val pressedAlpha: Float,
) : ModifierNodeElement<IosPressableNode>() {
    override fun create(): IosPressableNode =
        IosPressableNode(interactionSource, pressedScale, pressedAlpha)

    override fun update(node: IosPressableNode) {
        node.update(interactionSource, pressedScale, pressedAlpha)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "iosPressable"
        properties["pressedScale"] = pressedScale
        properties["pressedAlpha"] = pressedAlpha
    }
}

private class IosPressableNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
    private var pressedAlpha: Float,
) : Modifier.Node(), LayoutModifierNode {
    override val shouldAutoInvalidate: Boolean = false

    private var progress = Animatable(0f)
    private var collectJob: Job? = null
    private var layerBlock: GraphicsLayerScope.() -> Unit = pressLayerBlock()

    private fun pressLayerBlock(): GraphicsLayerScope.() -> Unit {
        val scale = pressedScale
        val alphaTarget = pressedAlpha
        return {
            val p = progress.value
            if (p != 0f) {
                val s = 1f + (scale - 1f) * p
                scaleX = s
                scaleY = s
                alpha = 1f + (alphaTarget - 1f) * p
            }
        }
    }

    override fun onAttach() {
        if (progress.value != 0f) progress = Animatable(0f)
        startCollecting()
    }

    override fun onDetach() {
        // The node's coroutine scope is cancelled on detach; only forget the job.
        collectJob = null
    }

    fun update(interactionSource: InteractionSource, pressedScale: Float, pressedAlpha: Float) {
        if (this.interactionSource != interactionSource) {
            this.interactionSource = interactionSource
            if (isAttached) {
                collectJob?.cancel()
                startCollecting()
            }
        }
        if (this.pressedScale != pressedScale || this.pressedAlpha != pressedAlpha) {
            this.pressedScale = pressedScale
            this.pressedAlpha = pressedAlpha
            // A new block instance makes the placement update the layer parameters.
            layerBlock = pressLayerBlock()
            invalidatePlacement()
        }
    }

    private fun startCollecting() {
        val source = interactionSource
        collectJob = coroutineScope.launch {
            val presses = ArrayList<PressInteraction.Press>(2)
            source.interactions.collect { interaction ->
                val wasPressed = presses.isNotEmpty()
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                    else -> return@collect
                }
                val pressed = presses.isNotEmpty()
                if (pressed == wasPressed) return@collect
                val tapped = interaction is PressInteraction.Release
                launch(start = CoroutineStart.UNDISPATCHED) {
                    if (pressed) {
                        progress.animateTo(1f, IosPress.downSpec)
                    } else {
                        // A quick tap inside a scrollable container reports Press and
                        // Release together. Show a short dip so the tap is still visible.
                        if (tapped && progress.value < 0.6f) {
                            progress.animateTo(1f, IosPress.downSpec)
                        }
                        progress.animateTo(0f, IosPress.releaseSpec)
                    }
                }
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}

/** Shared press timing: a quick ease-in dip and a critically-damped-ish spring back. */
internal object IosPress {
    private val easeIn = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

    val downSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 80, easing = easeIn)
    val releaseSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 480f)
}

// ---------------------------------------------------------------------------
// Window backdrop blur for dialogs and sheets

/**
 * Blurs everything behind the current dialog window with Android 12+ window blur
 * (FLAG_BLUR_BEHIND + blurBehindRadius). SurfaceFlinger composites it, so it costs the
 * app no offscreen rendering.
 *
 * Call it from inside a Dialog (or anything hosted by a [DialogWindowProvider]).
 * Returns whether the blur is effective right now. It turns false below API 31, on
 * low-RAM devices, and while battery saver or a developer setting disables cross-window
 * blur; callers should then use a denser scrim or [GlassTone.Thick] so text stays readable.
 */
@Composable
fun rememberDialogWindowBackdropBlur(radius: Dp = 30.dp): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(view) { view.findDialogWindow() }
    val supported = remember(context) { isWindowBlurSupported(context) }
    if (window == null || !supported) return false
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }
    val windowManager = window.windowManager
    var enabled by remember(window) { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(window, radiusPx) {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.also { it.blurBehindRadius = radiusPx }
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

/** Scrim behind a dialog whose window blur is active: the blur already separates layers. */
val DialogScrimBlurred = Color.Black.copy(alpha = 0.12f)

/** Scrim when window blur is unavailable: dense enough that the backdrop cannot compete. */
val DialogScrimOpaque = Color.Black.copy(alpha = 0.32f)

private fun isWindowBlurSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val activityManager = context.getSystemService(ActivityManager::class.java)
    return activityManager?.isLowRamDevice != true
}

private fun View.findDialogWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}

// ---------------------------------------------------------------------------
// Continuous-curvature corners (iOS squircle)

/**
 * Rounded rectangle with continuous curvature, like iOS `cornerCurve = .continuous`.
 * The curvature ramps up smoothly from the straight edge instead of jumping to a
 * circular arc. Use it for cards, sections, tiles and icons; use [CapsuleShape] for
 * fully rounded controls.
 */
@Immutable
class ContinuousRoundedShape(val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { radius.toPx() }
        val path = Path()
        path.addContinuousRoundedRect(size.width, size.height, radiusPx)
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousRoundedShape && other.radius == radius

    override fun hashCode(): Int = radius.hashCode()

    override fun toString(): String = "ContinuousRoundedShape($radius)"
}

/**
 * Shadow-only circular-arc rounded rectangle that matches the visual size of a
 * [ContinuousRoundedShape] with the same [radius].
 *
 * With a generic path outline HWUI cannot use its analytic rounded-rect shadow and
 * re-tessellates the shadow on the CPU whenever the caster is scaled (every frame of a
 * press animation). A blurred shadow hides the curvature difference. The factor makes
 * the outlines meet at the corner midpoints, since continuous corners extend further
 * along the edges than circular ones.
 */
fun continuousShadowShape(radius: Dp): Shape =
    RoundedCornerShape(radius * CONTINUOUS_SHADOW_RADIUS_SCALE)

private const val CONTINUOUS_SHADOW_RADIUS_SCALE = 1.2f

/**
 * Adds a continuous-curvature rounded rectangle spanning (0,0)-(width,height).
 * The coefficients are the widely published measurements of iOS 7+
 * `UIBezierPath(roundedRect:cornerRadius:)`. A corner reaches radius x 1.5287 along
 * each edge, so the radius is reduced when it would exceed half the shorter side.
 */
fun Path.addContinuousRoundedRect(width: Float, height: Float, radius: Float) {
    if (width <= 0f || height <= 0f) return
    val limit = min(width, height) / 2f / CONTINUOUS_EXTENT
    val r = radius.coerceIn(0f, limit)
    if (r <= 0f) {
        moveTo(0f, 0f)
        lineTo(width, 0f)
        lineTo(width, height)
        lineTo(0f, height)
        close()
        return
    }
    val w = width
    val h = height
    moveTo(CONTINUOUS_EXTENT * r, 0f)
    lineTo(w - CONTINUOUS_EXTENT * r, 0f)
    cubicTo(w - C_B * r, 0f, w - C_C * r, 0f, w - C_D * r, C_E * r)
    cubicTo(w - C_F * r, C_G * r, w - C_G * r, C_F * r, w - C_E * r, C_D * r)
    cubicTo(w, C_C * r, w, C_B * r, w, CONTINUOUS_EXTENT * r)
    lineTo(w, h - CONTINUOUS_EXTENT * r)
    cubicTo(w, h - C_B * r, w, h - C_C * r, w - C_E * r, h - C_D * r)
    cubicTo(w - C_G * r, h - C_F * r, w - C_F * r, h - C_G * r, w - C_D * r, h - C_E * r)
    cubicTo(w - C_C * r, h, w - C_B * r, h, w - CONTINUOUS_EXTENT * r, h)
    lineTo(CONTINUOUS_EXTENT * r, h)
    cubicTo(C_B * r, h, C_C * r, h, C_D * r, h - C_E * r)
    cubicTo(C_F * r, h - C_G * r, C_G * r, h - C_F * r, C_E * r, h - C_D * r)
    cubicTo(0f, h - C_C * r, 0f, h - C_B * r, 0f, h - CONTINUOUS_EXTENT * r)
    lineTo(0f, CONTINUOUS_EXTENT * r)
    cubicTo(0f, C_B * r, 0f, C_C * r, C_E * r, C_D * r)
    cubicTo(C_G * r, C_F * r, C_F * r, C_G * r, C_D * r, C_E * r)
    cubicTo(C_C * r, 0f, C_B * r, 0f, CONTINUOUS_EXTENT * r, 0f)
    close()
}

private const val CONTINUOUS_EXTENT = 1.52866483f
private const val C_B = 1.08849323f
private const val C_C = 0.86840689f
private const val C_D = 0.63149399f
private const val C_E = 0.07491100f
private const val C_F = 0.36994867f
private const val C_G = 0.17964654f

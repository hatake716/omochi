package io.github.hatake716.omochi

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * iOS 27 style building blocks for Omochi's light UI. Every animated value (press,
 * switch thumb, segment knob, spinner) is read in a draw or graphics-layer block, so
 * interacting with these components never recomposes their callers.
 */

private val SectionShape = ContinuousRoundedShape(26.dp)
private val SectionIconShape = ContinuousRoundedShape(7.dp)
private val BannerShape = RoundedCornerShape(28.dp)
private val MinTouchTarget = 48.dp

// ---------------------------------------------------------------------------
// Glass containers and buttons

/**
 * A floating Liquid Glass panel (toolbar, tab bar, card or menu body).
 *
 * The panel consumes touches that land on it, like a Material `Surface`, so gestures
 * never fall through to the WebView underneath. Content defaults to [IosColors.Label]
 * (white on [GlassTone.Tinted]). It does not clip; add `Modifier.clip(shape)` for
 * content that must stay inside the shape.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = SectionShape,
    tone: GlassTone = GlassTone.Regular,
    shadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val contentColor = if (tone == GlassTone.Tinted) Color.White else IosColors.Label
    Box(
        modifier = modifier
            .then(if (shadow) Modifier.glassShadow(shape) else Modifier)
            .liquidGlass(shape, tone)
            .semantics(mergeDescendants = false) { isTraversalGroup = true }
            .pointerInput(Unit) {},
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Round 48dp glass button for toolbars and navigation bars. It stretches into a
 * capsule when the modifier makes it wider. [active] switches to blue tinted glass
 * with a white glyph (for toggles such as a shown panel) and is exposed as "selected".
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val glyphColor = when {
        !enabled -> IosColors.TertiaryLabel
        active -> Color.White
        else -> IosColors.Label
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .iosPressable(interactionSource, pressedScale = 0.92f)
            .glassShadow(CapsuleShape, elevation = 4.dp)
            .liquidGlass(
                shape = CapsuleShape,
                tone = if (active && enabled) GlassTone.Tinted else GlassTone.Regular,
                highlight = 0.85f,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                if (active) selected = true
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = glyphColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Capsule button, at least 48dp tall.
 *
 * - `prominent = true`: blue filled (tinted glass) with white text, for the primary action.
 * - otherwise: glass capsule with blue text, or red text when [destructive].
 * - `prominent && destructive`: red filled.
 * Disabled buttons turn into plain glass with a tertiary label, as on iOS.
 */
@Composable
fun GlassCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val filled = prominent && enabled
    val contentColor = when {
        !enabled -> IosColors.TertiaryLabel
        filled -> Color.White
        destructive -> IosColors.RedText
        else -> IosColors.Blue
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .iosPressable(interactionSource)
            .glassShadow(CapsuleShape, elevation = if (filled) 6.dp else 4.dp)
            .liquidGlass(
                shape = CapsuleShape,
                tone = if (filled) GlassTone.Tinted else GlassTone.Regular,
                tint = if (destructive) IosColors.Red else IosColors.Blue,
                highlight = 0.9f,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            style = IosType.Headline,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Borderless text button in system blue (red when [destructive]), like "Done" or
 * "Cancel" in a navigation bar. It dims while pressed; the touch target is 48dp.
 */
@Composable
fun IosTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val color = when {
        !enabled -> IosColors.TertiaryLabel
        destructive -> IosColors.RedText
        else -> IosColors.Blue
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .iosPressable(interactionSource, pressedScale = 1f, pressedAlpha = 0.35f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = text,
            style = IosType.Body,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Grouped inset lists

/**
 * iOS "Settings" style section: a white card with 26dp continuous corners on the
 * grouped background. [header] is a footnote-sized section title (Latin letters are
 * upper-cased) and [footer] a footnote explanation. Put [IosListRow]s in [content]
 * and pass `showDivider = false` to the last one. The caller sets the horizontal
 * inset (usually 16dp).
 */
@Composable
fun IosGroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = IosType.Footnote,
                color = IosColors.SecondaryLabel,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
                    .semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Clipping keeps the row highlight inside the continuous corners.
                .clip(SectionShape)
                .background(IosColors.SecondaryGroupedBackground),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = IosType.Footnote,
                color = IosColors.SecondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
    }
}

/**
 * One row of an [IosGroupedSection], at least 48dp tall.
 *
 * Left to right: optional [icon] in a 29dp colored tile ([IosSectionIcon]), [title]
 * with an optional [subtitle], optional [value] in secondary text, optional
 * [trailing] content (a switch, a button) and an optional chevron. With [onClick] the
 * whole row is a button that highlights gray while pressed. The divider is a hairline
 * starting where the title starts; hide it on the last row.
 */
@Composable
fun IosListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = IosColors.Blue,
    value: String? = null,
    showChevron: Boolean = false,
    showDivider: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val textStart = if (icon != null) RowPadding + SectionIconSize + RowIconGap else RowPadding
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = IosRowHighlight,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .then(if (showDivider) Modifier.rowDivider(textStart) else Modifier)
            .heightIn(min = MinTouchTarget)
            .padding(start = RowPadding, end = RowPadding),
    ) {
        if (icon != null) {
            IosSectionIcon(
                icon = icon,
                tint = iconTint,
                modifier = if (enabled) Modifier else Modifier.alpha(0.4f),
            )
            Spacer(Modifier.width(RowIconGap))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp),
        ) {
            Text(
                text = title,
                style = IosType.Body,
                color = if (enabled) IosColors.Label else IosColors.TertiaryLabel,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = IosType.Footnote,
                    color = IosColors.SecondaryLabel,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = IosType.Body,
                color = IosColors.SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(max = 180.dp),
            )
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) { trailing() }
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = IosColors.TertiaryLabel,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(14.dp),
            )
        }
    }
}

/**
 * Settings-style icon: a white [icon] glyph on a 29dp [tint] tile with 7dp continuous
 * corners. Decorative; the row title carries the meaning.
 */
@Composable
fun IosSectionIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SectionIconSize)
            .background(tint, SectionIconShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

private val RowPadding = 16.dp
private val RowIconGap = 12.dp
private val SectionIconSize = 29.dp
private val HairlineWidth = 0.5.dp

/** Hairline at the bottom of a row, from [startInset] to the end edge (mirrored in RTL). */
private fun Modifier.rowDivider(startInset: Dp): Modifier = drawBehind {
    val stroke = HairlineWidth.toPx().coerceAtLeast(1f)
    val inset = startInset.toPx()
    val y = size.height - stroke / 2f
    val (startX, endX) = if (layoutDirection == LayoutDirection.Rtl) {
        0f to size.width - inset
    } else {
        inset to size.width
    }
    drawLine(
        color = IosColors.Separator,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = stroke,
    )
}

/**
 * iOS table-cell highlight: gray appears as soon as the row is pressed and fades out
 * after release. The fade is read in the draw phase only.
 */
private object IosRowHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        RowHighlightNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = javaClass.hashCode()
}

private class RowHighlightNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private val highlight = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = ArrayList<PressInteraction.Press>(2)
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                    else -> return@collect
                }
                val pressed = presses.isNotEmpty()
                launch(start = CoroutineStart.UNDISPATCHED) {
                    if (pressed) {
                        highlight.snapTo(1f)
                    } else {
                        highlight.animateTo(0f, tween(durationMillis = 280))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = highlight.value
        if (alpha > 0f) drawRect(IosColors.Gray4, alpha = alpha)
        drawContent()
    }
}

// ---------------------------------------------------------------------------
// Controls

/**
 * iOS toggle: a 51x31 track (green when on) with a white thumb. The thumb springs to
 * its position and stretches while pressed; both are animated in the draw phase.
 * The touch target is 51x48dp and it is announced as a switch.
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = iosSpring(),
        label = "iosSwitchThumb",
    )
    val stretch = remember { Animatable(0f) }
    LaunchedEffect(interactionSource) {
        val presses = ArrayList<PressInteraction.Press>(2)
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> presses.add(interaction)
                is PressInteraction.Release -> presses.remove(interaction.press)
                is PressInteraction.Cancel -> presses.remove(interaction.press)
                else -> return@collect
            }
            val target = if (presses.isEmpty()) 0f else 1f
            launch(start = CoroutineStart.UNDISPATCHED) { stretch.animateTo(target, iosSpring()) }
        }
    }
    Box(
        modifier = modifier
            .size(width = SwitchWidth, height = MinTouchTarget)
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .drawWithCache {
                val trackHeight = SwitchHeight.toPx()
                val trackTop = (size.height - trackHeight) / 2f
                val trackSize = Size(size.width, trackHeight)
                val trackRadius = CornerRadius(trackHeight / 2f)
                val inset = SwitchThumbInset.toPx()
                val thumbDiameter = trackHeight - inset * 2f
                val thumbRadius = thumbDiameter / 2f
                val stretchPx = SwitchThumbStretch.toPx()
                val travel = size.width - inset * 2f - thumbDiameter
                val thumbTop = trackTop + inset
                // Stepped rings approximate a soft drop shadow without a blur layer.
                val shadowDrop = 1.5.dp.toPx()
                val shadowSpreads = floatArrayOf(0.5.dp.toPx(), 1.5.dp.toPx(), 3.dp.toPx())
                val outline = Stroke(width = 0.5.dp.toPx())
                val rtl = layoutDirection == LayoutDirection.Rtl
                onDrawBehind {
                    val p = progress.value.coerceIn(0f, 1f)
                    val s = stretch.value.coerceAtLeast(0f) * stretchPx
                    drawRoundRect(
                        color = lerp(IosColors.SecondarySystemFill, IosColors.Green, p),
                        topLeft = Offset(0f, trackTop),
                        size = trackSize,
                        cornerRadius = trackRadius,
                    )
                    // The stretched thumb grows toward the side it will move to.
                    val ltrLeft = inset + travel * p - s * p
                    val left = if (rtl) size.width - ltrLeft - thumbDiameter - s else ltrLeft
                    val thumbSize = Size(thumbDiameter + s, thumbDiameter)
                    translate(left = left, top = thumbTop) {
                        for (i in shadowSpreads.indices) {
                            val spread = shadowSpreads[i]
                            drawRoundRect(
                                color = Color.Black,
                                alpha = SwitchShadowAlphas[i],
                                topLeft = Offset(-spread, shadowDrop - spread),
                                size = Size(thumbSize.width + spread * 2f, thumbSize.height + spread * 2f),
                                cornerRadius = CornerRadius(thumbRadius + spread),
                            )
                        }
                        drawRoundRect(
                            color = Color.White,
                            size = thumbSize,
                            cornerRadius = CornerRadius(thumbRadius),
                        )
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.04f),
                            size = thumbSize,
                            cornerRadius = CornerRadius(thumbRadius),
                            style = outline,
                        )
                    }
                }
            },
    )
}

private val SwitchWidth = 51.dp
private val SwitchHeight = 31.dp
private val SwitchThumbInset = 2.dp
private val SwitchThumbStretch = 6.dp
private val SwitchShadowAlphas = floatArrayOf(0.07f, 0.04f, 0.025f)

/**
 * iOS segmented control: a gray capsule track with a white knob that springs to the
 * selected segment. Each segment is a full 48dp-tall tab; the knob and the separators
 * are drawn from the animated position without recomposition.
 */
@Composable
fun IosSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = options.size.coerceAtLeast(1)
    val selected = selectedIndex.coerceIn(0, count - 1)
    val position = animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = iosSpring(),
        label = "iosSegmentKnob",
    )
    val selectedStyle = remember { IosType.Subheadline.copy(fontWeight = FontWeight.SemiBold) }
    Row(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            // Segments fill the row height, so the row must not take the parent's max height.
            .height(IntrinsicSize.Min)
            .drawWithCache {
                val trackInsetY = SegmentTrackInset.toPx()
                val trackHeight = size.height - trackInsetY * 2f
                val trackRadius = CornerRadius(trackHeight / 2f)
                val knobInset = SegmentKnobInset.toPx()
                val segmentWidth = size.width / count
                val knobHeight = trackHeight - knobInset * 2f
                val knobRadius = CornerRadius(knobHeight / 2f)
                val separatorHeight = trackHeight * 0.45f
                val separatorWidth = HairlineWidth.toPx().coerceAtLeast(1f)
                val shadowSpread = 0.5.dp.toPx()
                val shadowDrop = 1.dp.toPx()
                val rtl = layoutDirection == LayoutDirection.Rtl
                onDrawBehind {
                    drawRoundRect(
                        color = IosColors.TertiarySystemFill,
                        topLeft = Offset(0f, trackInsetY),
                        size = Size(size.width, trackHeight),
                        cornerRadius = trackRadius,
                    )
                    val p = position.value
                    for (i in 1 until count) {
                        val alpha = segmentSeparatorAlpha(i, p)
                        if (alpha <= 0f) continue
                        val x = if (rtl) size.width - i * segmentWidth else i * segmentWidth
                        drawLine(
                            color = IosColors.Separator,
                            start = Offset(x, (size.height - separatorHeight) / 2f),
                            end = Offset(x, (size.height + separatorHeight) / 2f),
                            strokeWidth = separatorWidth,
                            alpha = alpha,
                        )
                    }
                    val ltrLeft = p * segmentWidth + knobInset
                    val knobWidth = segmentWidth - knobInset * 2f
                    val left = if (rtl) size.width - ltrLeft - knobWidth else ltrLeft
                    val top = trackInsetY + knobInset
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.06f),
                        topLeft = Offset(left - shadowSpread, top - shadowSpread + shadowDrop),
                        size = Size(knobWidth + shadowSpread * 2f, knobHeight + shadowSpread * 2f),
                        cornerRadius = CornerRadius(knobRadius.x + shadowSpread),
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = Size(knobWidth, knobHeight),
                        cornerRadius = knobRadius,
                    )
                }
            }
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = isSelected,
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = label,
                    style = if (isSelected) selectedStyle else IosType.Subheadline,
                    color = IosColors.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SegmentTrackInset = 4.dp
private val SegmentKnobInset = 2.dp

/**
 * Opacity of the separator before segment [index] while the knob covers
 * [position, position + 1]: hidden where the knob touches it, fading in over a third
 * of a segment as the knob moves away.
 */
internal fun segmentSeparatorAlpha(index: Int, position: Float): Float {
    val distance = when {
        index < position -> position - index
        index > position + 1f -> index - (position + 1f)
        else -> 0f
    }
    return (distance * 3f).coerceIn(0f, 1f)
}

/**
 * iOS activity indicator: 12 rounded spokes whose opacity trails behind the leading
 * spoke. It steps one spoke every 1/12 s through the graphics layer only, so it costs
 * 12 layer updates per second instead of a frame every vsync, and it pauses with the
 * frame clock when the window is hidden.
 */
@Composable
fun IosActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = IosColors.Gray,
) {
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_STEP_MS)
            withFrameMillis { step.intValue = (step.intValue + 1) % SPINNER_SPOKES }
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .progressSemantics()
            .graphicsLayer { rotationZ = step.intValue * (360f / SPINNER_SPOKES) }
            .drawWithCache {
                val dimension = this.size.minDimension
                val width = dimension * 0.085f
                val inner = dimension * 0.23f
                val outer = dimension * 0.47f
                val colors = List(SPINNER_SPOKES) { k ->
                    color.copy(alpha = color.alpha * activityIndicatorSpokeAlpha(k))
                }
                onDrawBehind {
                    val c = center
                    for (k in 0 until SPINNER_SPOKES) {
                        // Spoke k trails the leading spoke (12 o'clock) counterclockwise.
                        rotate(degrees = -k * (360f / SPINNER_SPOKES), pivot = c) {
                            drawLine(
                                color = colors[k],
                                start = Offset(c.x, c.y - inner),
                                end = Offset(c.x, c.y - outer),
                                strokeWidth = width,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            },
    )
}

/** Opacity of the spoke [k] positions behind the leading one (0 = leading). */
internal fun activityIndicatorSpokeAlpha(k: Int): Float {
    val index = ((k % SPINNER_SPOKES) + SPINNER_SPOKES) % SPINNER_SPOKES
    return 1f - (1f - SPINNER_MIN_ALPHA) * index / (SPINNER_SPOKES - 1)
}

internal const val SPINNER_SPOKES = 12
private const val SPINNER_MIN_ALPHA = 0.22f
private const val SPINNER_STEP_MS = 1000L / SPINNER_SPOKES

// ---------------------------------------------------------------------------
// Banner and titles

/**
 * Floating notice at the top of the screen: a thick glass capsule with the [message]
 * and a 48dp close button ("閉じる"). It is a polite live region, so screen readers
 * announce it when it appears.
 */
@Composable
fun IosBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = BannerShape,
        tone = GlassTone.Thick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 20.dp, end = 4.dp),
        ) {
            Text(
                text = message,
                style = IosType.Subheadline,
                color = IosColors.Label,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
            )
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MinTouchTarget)
                    .iosPressable(interactionSource, pressedScale = 0.9f)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "閉じる" },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .background(IosColors.TertiarySystemFill, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = IosColors.SecondaryLabel,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * iOS large title (34sp bold) with an optional secondary [subtitle], marked as a
 * heading for screen readers. The caller provides the screen inset.
 */
@Composable
fun IosLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = IosType.LargeTitle,
            color = IosColors.Label,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = IosType.Subheadline,
                color = IosColors.SecondaryLabel,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

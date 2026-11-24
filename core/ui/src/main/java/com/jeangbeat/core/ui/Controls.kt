package com.jeangbeat.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * FL Studio-style knob. Drag up/right to increase, down/left to decrease;
 * double-tap resets to [default]. Values are 0..1 ([bipolar] draws from the centre).
 * [onChange] gets final = false while dragging and true when the finger lifts.
 */
@Composable
fun Knob(
    value: Float,
    onChange: (value: Float, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    default: Float = 0.5f,
    bipolar: Boolean = false,
    color: Color = JbColors.Accent,
    contentDescription: String = "",
    valueText: String = "",
    onLongPress: (() -> Unit)? = null,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = valueText.ifEmpty { "${(value * 100).toInt()}%" }
            }
            .pointerInput(default, onLongPress) {
                detectTapGestures(
                    onDoubleTap = { change(default, true) },
                    onLongPress = onLongPress?.let { cb -> { _: Offset -> cb() } },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var v = current
                    var moved = false
                    drag(down.id) { c ->
                        val d = c.positionChange()
                        if (!moved && abs(d.y) + abs(d.x) < 0.5f) return@drag
                        moved = true
                        c.consume()
                        v = (v + (d.x - d.y) / 180f).coerceIn(0f, 1f)
                        change(v, false)
                    }
                    if (moved) change(v, true)
                }
            },
    ) {
        val stroke = this.size.minDimension * 0.12f
        val inset = stroke
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(Color(0xFF2B303C), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val sweep = if (bipolar) (value - 0.5f) * 270f else value * 270f
        val start = if (bipolar) 270f else 135f
        drawArc(color, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val angle = Math.toRadians((135.0 + value * 270.0))
        val r = arcSize.width / 2 - stroke
        val c = center
        drawLine(
            JbColors.Text,
            c,
            Offset(c.x + (r * kotlin.math.cos(angle)).toFloat(), c.y + (r * kotlin.math.sin(angle)).toFloat()),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round,
        )
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChange(): Offset = position - previousPosition

/** Vertical mixer fader with stereo meters. [value] 0..1 (0.8 = 0 dB). */
@Composable
fun Fader(
    value: Float,
    onChange: (value: Float, final: Boolean) -> Unit,
    meterL: Float,
    meterR: Float,
    modifier: Modifier = Modifier,
    default: Float = 0.8f,
    contentDescription: String = "",
    valueText: String = "",
    onLongPress: (() -> Unit)? = null,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var heightPx by remember { mutableFloatStateOf(1f) }
    Canvas(
        modifier
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = valueText
            }
            .pointerInput(default, onLongPress) {
                detectTapGestures(
                    onDoubleTap = { change(default, true) },
                    onLongPress = onLongPress?.let { cb -> { _: Offset -> cb() } },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var v = current
                    var moved = false
                    drag(down.id) { c ->
                        val dy = c.position.y - c.previousPosition.y
                        if (!moved && abs(dy) < 0.5f) return@drag
                        moved = true
                        c.consume()
                        v = (v - dy / (heightPx * 0.85f)).coerceIn(0f, 1f)
                        change(v, false)
                    }
                    if (moved) change(v, true)
                }
            },
    ) {
        heightPx = size.height
        val trackW = 6.dp.toPx()
        val meterW = 5.dp.toPx()
        val cx = size.width / 2
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val span = bottom - top
        // meters
        fun meter(x: Float, level: Float) {
            drawRect(Color(0xFF0B0D11), Offset(x, top), Size(meterW, span))
            val h = span * level.coerceIn(0f, 1f)
            val c = when {
                level > 0.95f -> JbColors.Record
                level > 0.7f -> JbColors.Accent
                else -> Color(0xFF4CD964)
            }
            drawRect(c, Offset(x, bottom - h), Size(meterW, h))
        }
        meter(cx - trackW / 2 - meterW - 4.dp.toPx(), meterL)
        meter(cx + trackW / 2 + 4.dp.toPx(), meterR)
        // track + unity mark
        drawRoundRect(Color(0xFF0B0D11), Offset(cx - trackW / 2, top), Size(trackW, span), androidx.compose.ui.geometry.CornerRadius(3f))
        val unityY = bottom - span * 0.8f
        drawLine(JbColors.TextDim, Offset(cx - 12.dp.toPx(), unityY), Offset(cx + 12.dp.toPx(), unityY), 1f)
        // cap
        val capY = bottom - span * value.coerceIn(0f, 1f)
        val capW = 26.dp.toPx()
        val capH = 14.dp.toPx()
        drawRoundRect(Color(0xFFC9CCD4), Offset(cx - capW / 2, capY - capH / 2), Size(capW, capH), androidx.compose.ui.geometry.CornerRadius(4f))
        drawLine(Color(0xFF555555), Offset(cx - capW / 2 + 3, capY), Offset(cx + capW / 2 - 3, capY), 1.5f)
    }
}

/** Square tool button used in FL-style toolbars. */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) JbColors.Accent else Color.Transparent)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Color.Black else JbColors.TextDim, modifier = Modifier.size(18.dp))
    }
}

/** A compact chip that opens a dropdown of [options]. */
@Composable
fun <T> DropdownChip(
    text: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    leading: Color? = null,
    extra: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(JbColors.SurfaceRaised)
                .clickable { open = true }
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(leading))
                Box(Modifier.width(5.dp))
            }
            Text(
                "$text ▾",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) JbColors.Accent else JbColors.Text,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
            extra?.invoke { open = false }
        }
    }
}

/** Small labelled value under a knob. */
@Composable
fun KnobWithLabel(
    label: String,
    value: Float,
    valueText: String,
    onChange: (Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    default: Float = 0.5f,
    bipolar: Boolean = false,
    size: Dp = 40.dp,
    onLongPress: (() -> Unit)? = null,
) {
    Column(modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(value, onChange, size = size, default = default, bipolar = bipolar, contentDescription = label, valueText = valueText, onLongPress = onLongPress)
        Text(valueText, fontSize = 10.sp, color = JbColors.Text, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, fontSize = 10.sp, color = JbColors.TextDim, maxLines = 1)
    }
}

/** Mute LED used in the channel rack (green = on). */
@Composable
fun MuteLed(on: Boolean, solo: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit, contentDescription: String) {
    val toggle by rememberUpdatedState(onToggle)
    val longPress by rememberUpdatedState(onLongPress)
    Box(
        Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }, onLongPress = { longPress() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on) Color(0xFF4CD964) else Color(0xFF2B303C))
                .then(if (solo) Modifier.border(2.dp, JbColors.Accent, RoundedCornerShape(50)) else Modifier),
        )
    }
}

package com.jeangbeat.feature.playlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.AutoPoint
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Edits an automation clip's curve: tap to add a point, drag a point to move
 * it, long-press a point to delete it. Points snap to 1/16 steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationEditorSheet(
    project: Project,
    clip: Clip.AutomationClip,
    onPoints: (List<AutoPoint>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val current by rememberUpdatedState(clip)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = JbColors.Surface) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.edit_points) + " · " + automationLabel(project, clip), style = MaterialTheme.typography.titleMedium)
            Text("${clip.lengthTicks / Timing.TICKS_PER_BAR} bars · ${clip.points.size} points", fontSize = 11.sp, color = JbColors.TextDim)
            Canvas(
                Modifier
                    .padding(vertical = 12.dp)
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0B0D11))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val c = current
                            fun tickAt(x: Float) = ((x / size.width) * c.lengthTicks / Timing.TICKS_PER_STEP).roundToInt() * Timing.TICKS_PER_STEP
                            fun valueAt(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                            fun pointPos(p: AutoPoint) = Offset(p.tick.toFloat() / c.lengthTicks * size.width, (1f - p.value) * size.height)
                            val hitIndex = c.points.indexOfFirst { (pointPos(it) - down.position).getDistance() < 28f }
                            var points = c.points.toMutableList()
                            var moved = false
                            var longPress = false
                            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.changes.none { it.pressed }) return@withTimeoutOrNull true
                                    if ((e.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                                }
                                @Suppress("UNREACHABLE_CODE") false
                            }
                            if (released == null) longPress = true
                            if (longPress && hitIndex >= 0 && points.size > 1) {
                                points.removeAt(hitIndex)
                                onPoints(points, true)
                                return@awaitEachGesture
                            }
                            if (released == true) {
                                if (hitIndex < 0) {
                                    points.add(AutoPoint(tickAt(down.position.x).coerceIn(0, c.lengthTicks), valueAt(down.position.y)))
                                    onPoints(points.sortedBy { it.tick }, true)
                                }
                                return@awaitEachGesture
                            }
                            // Drag: move the touched point (or add one and move it).
                            var index = hitIndex
                            if (index < 0) {
                                points.add(AutoPoint(tickAt(down.position.x).coerceIn(0, c.lengthTicks), valueAt(down.position.y)))
                                points = points.sortedBy { it.tick }.toMutableList()
                                index = points.indexOfFirst { abs(it.tick - tickAt(down.position.x)) == 0 }
                            }
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                moved = true
                                if (index in points.indices) {
                                    points[index] = AutoPoint(tickAt(ch.position.x).coerceIn(0, c.lengthTicks), valueAt(ch.position.y))
                                    onPoints(points.toList(), false)
                                }
                            }
                            if (moved) onPoints(points.sortedBy { it.tick }, true)
                        }
                    },
            ) {
                val bars = clip.lengthTicks / Timing.TICKS_PER_BAR
                for (b in 0..bars) {
                    val x = b.toFloat() / bars.coerceAtLeast(1) * size.width
                    drawLine(JbColors.GridBeat, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                drawLine(JbColors.Outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                val path = Path()
                clip.points.forEachIndexed { i, p ->
                    val o = Offset(p.tick.toFloat() / clip.lengthTicks * size.width, (1f - p.value) * size.height)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, JbColors.Accent, style = Stroke(2.5f))
                clip.points.forEach { p ->
                    drawCircle(JbColors.Accent, 7.dp.toPx(), Offset(p.tick.toFloat() / clip.lengthTicks * size.width, (1f - p.value) * size.height))
                }
            }
            Row {
                Text("Tap: add · drag: move · long-press: delete", fontSize = 11.sp, color = JbColors.TextDim, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

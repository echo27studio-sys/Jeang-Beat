package com.jeangbeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R

/** Finger-drum pads, one per channel (first 16). Hits are recorded while Record is on. */
@Composable
fun PerformPads(
    channels: List<Channel>,
    recording: Boolean,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pads = channels.take(16)
    val columns = 4
    Column(modifier.background(JbColors.Surface).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.perform_pads) + if (recording) "  ● REC" else "",
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = if (recording) Color(0xFFE5484D) else JbColors.Text,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
        pads.chunked(columns).forEach { rowPads ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowPads.forEach { ch -> Pad(ch, onDown, onUp, Modifier.weight(1f)) }
                repeat(columns - rowPads.size) { Box(Modifier.weight(1f)) }
            }
            Box(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Pad(channel: Channel, onDown: (Int) -> Unit, onUp: (Int) -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val color = JbColors.channel(channel.color)
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) color else color.copy(alpha = 0.35f))
            .pointerInput(channel.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onDown(channel.id)
                    waitForUpOrCancellation()
                    pressed = false
                    onUp(channel.id)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            channel.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = if (pressed) Color(0xFF0F1115) else JbColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
    }
}

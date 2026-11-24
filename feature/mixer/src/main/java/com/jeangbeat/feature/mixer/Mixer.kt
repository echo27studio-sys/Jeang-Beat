package com.jeangbeat.feature.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.AutomationTarget
import com.jeangbeat.core.model.EFFECT_SLOTS
import com.jeangbeat.core.model.EffectSlot
import com.jeangbeat.core.model.EffectType
import com.jeangbeat.core.model.MixerTrack
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.channelsOn
import com.jeangbeat.core.model.faderDb
import com.jeangbeat.core.model.sidechainSource
import com.jeangbeat.core.model.wouldCreateCycle
import com.jeangbeat.core.ui.Fader
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.Knob
import com.jeangbeat.core.ui.KnobWithLabel
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.SegmentedChoice
import kotlin.math.roundToInt

interface MixerActions {
    fun setVolume(track: Int, volume: Float, final: Boolean)
    fun setPan(track: Int, pan: Float, final: Boolean)
    fun toggleMute(track: Int)
    fun toggleSolo(track: Int)
    fun rename(track: Int)
    fun addEffect(track: Int, type: EffectType)
    fun removeEffect(track: Int, slotId: Int)
    fun moveEffect(track: Int, slotId: Int, toPosition: Int)
    fun setEffectEnabled(track: Int, slotId: Int, enabled: Boolean)
    fun setEffectMix(track: Int, slotId: Int, mix: Float, final: Boolean)
    fun setEffectParam(track: Int, slotId: Int, param: Int, value: Float, final: Boolean)
    fun setSidechain(track: Int, slotId: Int, source: Int)
    fun setSend(from: Int, to: Int, level: Float, final: Boolean)
    fun setToMaster(track: Int, on: Boolean)

    /** Adds an automation clip for [target] to the Playlist, starting at [value] (0..1). */
    fun automate(target: AutomationTarget, value: Float)
}

private enum class PanelTab { EFFECTS, ROUTING }

/** FL Studio-style mixer: strips, then the selected insert's effects or routing. */
@Composable
fun Mixer(
    project: Project,
    meters: FloatArray,
    selected: Int,
    onSelect: (Int) -> Unit,
    actions: MixerActions,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) } // track, slot id
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxWidth > 600.dp
        val track = project.mixer.track(selected) ?: project.mixer.master
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Strips(project, meters, selected, onSelect, actions, Modifier.weight(1f).fillMaxHeight())
                Panel(project, track, actions, { editing = track.index to it }, Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Strips(project, meters, selected, onSelect, actions, Modifier.fillMaxWidth().height(300.dp))
                Panel(project, track, actions, { editing = track.index to it }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    editing?.let { (t, slotId) ->
        val slot = project.mixer.track(t)?.effects?.firstOrNull { it.id == slotId }
        if (slot == null) {
            editing = null
        } else {
            EffectEditorSheet(project, t, slot, actions) { editing = null }
        }
    }
}

@Composable
private fun Strips(project: Project, meters: FloatArray, selected: Int, onSelect: (Int) -> Unit, actions: MixerActions, modifier: Modifier) {
    LazyRow(
        modifier.background(Color(0xFF101318)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(project.mixer.tracks, key = { it.index }) { t ->
            Strip(project, t, meters.getOrElse(t.index * 2) { 0f }, meters.getOrElse(t.index * 2 + 1) { 0f }, t.index == selected, actions) { onSelect(t.index) }
        }
    }
}

@Composable
private fun Strip(project: Project, t: MixerTrack, meterL: Float, meterR: Float, selected: Boolean, actions: MixerActions, onSelect: () -> Unit) {
    val color = if (t.color >= 0) JbColors.channel(t.color) else JbColors.Outline
    Column(
        Modifier
            .width(62.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (t.isMaster) Color(0xFF1E1A10) else JbColors.Surface)
            .then(if (selected) Modifier.border(2.dp, JbColors.Accent, RoundedCornerShape(8.dp)) else Modifier)
            .pointerInput(t.index) { detectTapGestures(onTap = { onSelect() }, onLongPress = { actions.rename(t.index) }) }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(if (t.isMaster) "M" else "${t.index}", fontSize = 9.sp, color = JbColors.TextDim, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(t.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(minOf(5, maxOf(t.effects.size, 3))) { i ->
                val on = i < t.effects.size && t.effects[i].enabled
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(if (on) JbColors.channel(3) else Color(0xFF2B303C)))
            }
        }
        Knob(
            value = (t.pan + 1f) / 2f,
            onChange = { v, f -> actions.setPan(t.index, v * 2f - 1f, f) },
            size = 24.dp, bipolar = true,
            contentDescription = stringResource(R.string.pan) + " " + t.name,
            onLongPress = { actions.automate(AutomationTarget.MixerPan(t.index), (t.pan + 1f) / 2f) },
        )
        Fader(
            value = t.volume,
            onChange = { v, f -> actions.setVolume(t.index, v, f) },
            meterL = meterL, meterR = meterR,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentDescription = stringResource(R.string.volume) + " " + t.name,
            valueText = dbText(t.volume),
            onLongPress = { actions.automate(AutomationTarget.MixerVolume(t.index), t.volume) },
        )
        Text(dbText(t.volume), fontSize = 9.sp, color = JbColors.TextDim, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            SmallButton("M", t.muted, JbColors.Record) { actions.toggleMute(t.index) }
            if (!t.isMaster) SmallButton("S", t.solo, JbColors.Accent) { actions.toggleSolo(t.index) }
        }
        Text(
            if (t.isMaster) "OUT" else if (t.toMaster) "→ M" else if (t.sends.isNotEmpty()) "→ ${t.sends.first().target}" else "—",
            fontSize = 9.sp, color = JbColors.Accent, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SmallButton(text: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 20.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(if (on) color else JbColors.SurfaceRaised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 9.sp, fontWeight = FontWeight.Black, color = if (on) Color.Black else JbColors.TextDim) }
}

private fun dbText(volume: Float): String {
    val db = faderDb(volume)
    return if (db.isInfinite()) "-∞" else "%+.1f".format(db)
}

@Composable
private fun Panel(project: Project, track: MixerTrack, actions: MixerActions, onEdit: (Int) -> Unit, modifier: Modifier) {
    var tab by rememberSaveable { mutableStateOf(PanelTab.EFFECTS) }
    Column(modifier.background(JbColors.Surface).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (track.color >= 0) JbColors.channel(track.color) else JbColors.Outline))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (track.isMaster) "" else "Insert ${track.index} · ") + track.name,
                fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1,
            )
            SegmentedChoice(
                PanelTab.entries, tab,
                { stringResource(if (it == PanelTab.EFFECTS) R.string.effects else R.string.routing) },
                { tab = it },
            )
        }
        val channels = project.channelsOn(track.index)
        if (channels.isNotEmpty()) {
            Text(stringResource(R.string.channels_here, channels.joinToString { it.name }), fontSize = 10.sp, color = JbColors.TextDim, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            when (tab) {
                PanelTab.EFFECTS -> EffectsTab(project, track, actions, onEdit)
                PanelTab.ROUTING -> RoutingTab(project, track, actions)
            }
        }
    }
}

@Composable
private fun EffectsTab(project: Project, track: MixerTrack, actions: MixerActions, onEdit: (Int) -> Unit) {
    track.effects.forEachIndexed { i, slot ->
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF13161C)).clickable { onEdit(slot.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${i + 1}", fontSize = 10.sp, color = JbColors.TextDim, fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
            Box(
                Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(if (slot.enabled) Color(0xFF4CD964) else Color(0xFF2B303C))
                    .clickable { actions.setEffectEnabled(track.index, slot.id, !slot.enabled) },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(slot.type.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (slot.enabled) JbColors.Text else JbColors.TextDim)
                slot.sidechainSource()?.let { sc ->
                    Text("◀ SC: $sc ${project.mixer.track(sc)?.name ?: ""}", fontSize = 10.sp, color = JbColors.Accent)
                }
            }
            Text(stringResource(R.string.mix).uppercase(), fontSize = 9.sp, color = JbColors.TextDim)
            Spacer(Modifier.width(4.dp))
            Knob(slot.mix, { v, f -> actions.setEffectMix(track.index, slot.id, v, f) }, size = 26.dp, default = slot.type.defaultMix, contentDescription = stringResource(R.string.mix))
            Box {
                Text("⋮", modifier = Modifier.clickable { menu = true }.padding(horizontal = 8.dp), fontSize = 18.sp, color = JbColors.TextDim)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (i > 0) DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menu = false; actions.moveEffect(track.index, slot.id, i - 1) })
                    if (i < track.effects.lastIndex) DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menu = false; actions.moveEffect(track.index, slot.id, i + 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; actions.removeEffect(track.index, slot.id) })
                }
            }
        }
    }
    if (track.effects.size < EFFECT_SLOTS) {
        var open by remember { mutableStateOf(false) }
        Box {
            Text(
                "＋ " + stringResource(R.string.add_effect),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, JbColors.Outline, RoundedCornerShape(8.dp)).clickable { open = true }.padding(10.dp),
                color = JbColors.TextDim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                EffectType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(type.displayName) }, onClick = { open = false; actions.addEffect(track.index, type) })
                }
            }
        }
    }
    // Quick EQ preview for the first EQ on this track.
    track.effects.firstOrNull { it.type == EffectType.EQ }?.let { EqCurve(it, Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp)) }
}

@Composable
private fun RoutingTab(project: Project, track: MixerTrack, actions: MixerActions) {
    if (track.isMaster) {
        Text("Master → output", color = JbColors.TextDim)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.to_master), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = track.toMaster, onCheckedChange = { actions.setToMaster(track.index, it) })
    }
    project.mixer.tracks.filter { !it.isMaster && it.index != track.index }.forEach { target ->
        val level = track.sends.firstOrNull { it.target == target.index }?.level ?: 0f
        val blocked = level == 0f && project.wouldCreateCycle(track.index, target.index)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "→ ${target.index} ${target.name}",
                fontSize = 12.sp, modifier = Modifier.width(120.dp), maxLines = 1,
                color = if (blocked) JbColors.TextDim.copy(alpha = 0.5f) else if (level > 0f) JbColors.Accent else JbColors.Text,
            )
            Slider(
                value = level,
                onValueChange = { actions.setSend(track.index, target.index, it, false) },
                onValueChangeFinished = { actions.setSend(track.index, target.index, level, true) },
                enabled = !blocked,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = JbColors.Accent, activeTrackColor = JbColors.Accent),
            )
            Text("${(level * 100).roundToInt()}%", fontSize = 11.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EffectEditorSheet(project: Project, track: Int, slot: EffectSlot, actions: MixerActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = JbColors.Surface) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slot.type.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.enabled))
                Switch(checked = slot.enabled, onCheckedChange = { actions.setEffectEnabled(track, slot.id, it) })
            }
            Text(
                "Insert $track · ${project.mixer.track(track)?.name ?: ""} · long-press a knob to automate",
                fontSize = 11.sp, color = JbColors.TextDim,
            )
            if (slot.type == EffectType.EQ) EqCurve(slot, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp))
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                slot.type.params.forEachIndexed { i, spec ->
                    if (slot.type == EffectType.COMPRESSOR && i == 5) return@forEachIndexed // sidechain has its own picker
                    val value = slot.param(i)
                    if (spec.stepped && spec.options.isNotEmpty()) {
                        Column {
                            Text(spec.key, fontSize = 10.sp, color = JbColors.TextDim)
                            SegmentedChoice(spec.options.indices.toList(), value.roundToInt(), { spec.options[it] }, { actions.setEffectParam(track, slot.id, i, it.toFloat(), true) })
                        }
                    } else {
                        KnobWithLabel(
                            label = spec.key,
                            value = spec.normalize(value),
                            valueText = formatValue(value, spec.unit),
                            onChange = { v, f -> actions.setEffectParam(track, slot.id, i, spec.denormalize(v), f) },
                            default = spec.normalize(spec.default),
                            bipolar = spec.min < 0f && spec.max > 0f,
                            onLongPress = { actions.automate(AutomationTarget.EffectParam(track, slot.id, i), spec.normalize(value)) },
                        )
                    }
                }
                KnobWithLabel(
                    label = stringResource(R.string.mix),
                    value = slot.mix,
                    valueText = "${(slot.mix * 100).roundToInt()}%",
                    onChange = { v, f -> actions.setEffectMix(track, slot.id, v, f) },
                    default = slot.type.defaultMix,
                )
            }
            if (slot.type == EffectType.COMPRESSOR) {
                var open by remember { mutableStateOf(false) }
                val source = slot.sidechainSource()
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sidechain), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box {
                        Text(
                            source?.let { "$it ${project.mixer.track(it)?.name ?: ""}" } ?: stringResource(R.string.none),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(JbColors.SurfaceRaised).clickable { open = true }.padding(10.dp),
                            color = JbColors.Accent, fontWeight = FontWeight.Bold,
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.none)) }, onClick = { open = false; actions.setSidechain(track, slot.id, -1) })
                            project.mixer.tracks.filter { !it.isMaster && it.index != track && !project.wouldCreateCycle(it.index, track) }.forEach { t ->
                                DropdownMenuItem(text = { Text("${t.index} ${t.name}") }, onClick = { open = false; actions.setSidechain(track, slot.id, t.index) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatValue(v: Float, unit: String): String = when {
    unit == "Hz" && v >= 1000f -> "%.1fk".format(v / 1000f)
    unit == "Hz" -> "${v.roundToInt()}"
    unit == "ms" && v < 10f -> "%.1f ms".format(v)
    unit == "ms" -> "${v.roundToInt()} ms"
    unit == "dB" -> "%+.1f dB".format(v)
    unit == ":1" -> "%.1f:1".format(v)
    unit.isEmpty() -> "%.2f".format(v)
    else -> "%.1f %s".format(v, unit)
}

/** Draws the EQ's magnitude response (same RBJ filters as the engine). */
@Composable
private fun EqCurve(slot: EffectSlot, modifier: Modifier) {
    val response = remember(slot.params) { EqResponse.curve(slot.params, 120) }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0D11))) {
        val mid = size.height / 2
        drawLine(JbColors.Outline, Offset(0f, mid), Offset(size.width, mid), 1f)
        listOf(100f, 1000f, 10000f).forEach { f ->
            val x = EqResponse.xOf(f) * size.width
            drawLine(Color(0xFF1E222B), Offset(x, 0f), Offset(x, size.height), 1f)
        }
        val path = Path()
        response.forEachIndexed { i, db ->
            val x = i.toFloat() / (response.size - 1) * size.width
            val y = mid - (db / 24f) * mid
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, JbColors.channel(3), style = Stroke(2.dp.toPx()))
    }
}

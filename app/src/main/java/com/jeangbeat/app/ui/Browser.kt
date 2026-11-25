package com.jeangbeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.app.browser.BrowserItem
import com.jeangbeat.core.data.browser.BrowserQuery
import com.jeangbeat.core.data.browser.IndexedSample
import com.jeangbeat.core.data.browser.SampleCategory
import com.jeangbeat.core.model.DrumSound
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.browserDragSource
import com.jeangbeat.core.ui.label

/**
 * FL Studio-style Browser: built-in sounds, project samples, recent files and
 * linked folders. Tap to preview, long-press and drag onto the Channel Rack,
 * Playlist, Mixer or Piano Roll; ⋮ adds without dragging.
 */
@Composable
fun BrowserPanel(
    vm: StudioViewModel,
    project: Project,
    onAddFolder: () -> Unit,
    onClose: () -> Unit,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bs by vm.browser.state.collectAsStateWithLifecycle()
    val index = bs.index
    var text by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<SampleCategory?>(null) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }
    var autoPreview by remember { mutableStateOf(vm.autoPreview) }
    var selected by remember { mutableStateOf<BrowserItem?>(null) }
    // Expanded sections and sub-folders ("folderUri|dir"); folders start open, sub-folders closed.
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val query = BrowserQuery(text = text, category = category, favouritesOnly = favouritesOnly)

    val builtIn = builtInSounds()
    val tap = { item: BrowserItem ->
        selected = item
        if (autoPreview) vm.preview(item)
    }
    val row: @Composable (BrowserItem, Int) -> Unit = { item, indent ->
        BrowserRow(
            item, indent, selected == item, index.favourites,
            onTap = { tap(item) },
            onPreview = { vm.preview(item) },
            onFavourite = { (item as? BrowserItem.File)?.let { vm.browser.toggleFavourite(it.sample.key2) } },
            onAddChannel = { vm.addFromBrowser(item) },
            onAddPlaylist = { vm.addToPlaylistFromBrowser(item) },
            onDragStart = { vm.stopPreview(); onDragStart() },
        )
    }

    Column(modifier.background(JbColors.Surface)) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.browser), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { autoPreview = !autoPreview; vm.autoPreview = autoPreview }) {
                Icon(
                    if (autoPreview) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    stringResource(R.string.auto_preview), tint = if (autoPreview) JbColors.Accent else JbColors.TextDim,
                )
            }
            IconButton(onClick = vm.browser::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            IconButton(onClick = onAddFolder) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.add_folder), tint = JbColors.Accent) }
            IconButton(onClick = { vm.stopPreview(); onClose() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            placeholder = { Text(stringResource(R.string.search_samples), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(favouritesOnly, { favouritesOnly = !favouritesOnly }, label = { Text("★") })
            SampleCategory.entries.forEach { c ->
                FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(stringResource(c.label()), fontSize = 12.sp) })
            }
        }
        bs.scanning?.let { n ->
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = JbColors.Accent)
            Text(stringResource(R.string.scanning_files, n), fontSize = 11.sp, color = JbColors.TextDim, modifier = Modifier.padding(horizontal = 12.dp))
        }

        val tBuiltIn = stringResource(R.string.built_in_sounds)
        val tProject = stringResource(R.string.project_samples)
        val tRecent = stringResource(R.string.recent)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (!query.isEmpty) {
                // Search / filter results: one flat list.
                val sounds = if (favouritesOnly || category != null && category != SampleCategory.DRUMS && category != SampleCategory.INSTRUMENTS) {
                    emptyList()
                } else {
                    builtIn.filter { s -> text.isBlank() || s.label.contains(text.trim(), ignoreCase = true) }
                        .filter { s -> category == null || (category == SampleCategory.DRUMS) == (s.instrument is Instrument.Drum) }
                }
                val files = query.apply(index)
                items(sounds, key = { "s:" + it.label }) { row(it, 0) }
                items(files, key = { "f:" + it.key2 }) { row(BrowserItem.File(it), 0) }
                if (sounds.isEmpty() && files.isEmpty()) item { Hint(stringResource(R.string.no_results)) }
                return@LazyColumn
            }

            section("builtin", tBuiltIn, Icons.Default.GraphicEq, collapsed, { collapsed = it }) {
                items(builtIn, key = { "s:" + it.label }) { row(it, 1) }
            }
            if (project.samples.isNotEmpty()) {
                section("project", tProject, Icons.Default.AudioFile, collapsed, { collapsed = it }) {
                    items(project.samples, key = { "p:" + it.id }) { row(BrowserItem.ProjectSample(it), 1) }
                }
            }
            val byKey = index.files.associateBy { it.key2 }
            val recent = index.recent.mapNotNull { byKey[it] }
            if (recent.isNotEmpty()) {
                section("recent", tRecent, Icons.Default.Refresh, collapsed, { collapsed = it }, startCollapsed = true) {
                    items(recent, key = { "r:" + it.key2 }) { row(BrowserItem.File(it), 1) }
                }
            }
            if (index.folders.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.browser_empty), fontSize = 13.sp, color = JbColors.TextDim)
                        TextButton(onClick = onAddFolder) { Text("＋ " + stringResource(R.string.add_folder)) }
                    }
                }
            }
            for (folder in index.folders) {
                val lost = folder.uri in bs.lost
                val id = "folder:" + folder.uri
                val open = id !in collapsed && !lost
                item(key = id) {
                    FolderHeader(
                        folder.name, lost, open,
                        count = index.files.count { it.folder == folder.uri },
                        onToggle = { collapsed = if (id in collapsed) collapsed - id else collapsed + id },
                        onReconnect = onAddFolder,
                        onRemove = { vm.browser.removeFolder(folder.uri) },
                    )
                }
                if (!open) continue
                val files = index.files.filter { it.folder == folder.uri }
                folderTree(folder.uri, files, expanded, { expanded = it }, row)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        Text(
            stringResource(R.string.browser_hint),
            fontSize = 10.sp, color = JbColors.TextDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Sub-folders (collapsible) and files of one linked folder. */
private fun LazyListScope.folderTree(
    folderUri: String,
    files: List<IndexedSample>,
    expanded: Set<String>,
    setExpanded: (Set<String>) -> Unit,
    row: @Composable (BrowserItem, Int) -> Unit,
) {
    val byDir = files.groupBy { it.dir }
    // Every directory, including parents that only hold folders.
    val dirs = byDir.keys.flatMap { d -> d.split('/').indices.map { i -> d.split('/').take(i + 1).joinToString("/") } }
        .filter { it.isNotEmpty() }.toSortedSet(compareBy<String> { it.lowercase() })
    fun visible(dir: String): Boolean {
        // A directory is shown when all its parents are expanded.
        val parts = dir.split('/')
        return (1 until parts.size).all { i -> "$folderUri|${parts.take(i).joinToString("/")}" in expanded }
    }
    // Top-level files first, then each visible directory with its files.
    items(byDir[""].orEmpty(), key = { "f:" + it.key2 }) { row(BrowserItem.File(it), 1) }
    for (dir in dirs) {
        if (!visible(dir)) continue
        val key = "$folderUri|$dir"
        val depth = dir.count { it == '/' } + 1
        val open = key in expanded
        item(key = "d:$key") {
            Row(
                Modifier.fillMaxWidth().clickable { setExpanded(if (open) expanded - key else expanded + key) }.padding(start = (8 + depth * 12).dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = JbColors.TextDim)
                Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = JbColors.Accent.copy(alpha = 0.8f))
                Spacer(Modifier.width(6.dp))
                Text(dir.substringAfterLast('/'), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (open) items(byDir[dir].orEmpty(), key = { "f:" + it.key2 }) { row(BrowserItem.File(it), depth + 1) }
    }
}

private fun LazyListScope.section(
    id: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    collapsed: Set<String>,
    setCollapsed: (Set<String>) -> Unit,
    startCollapsed: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    // For sections that start collapsed, the set holds the *opened* marker instead.
    val marker = if (startCollapsed) "open:$id" else id
    val open = if (startCollapsed) marker in collapsed else marker !in collapsed
    item(key = "sec:$id") {
        Row(
            Modifier.fillMaxWidth().clickable { setCollapsed(if (marker in collapsed) collapsed - marker else collapsed + marker) }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = JbColors.TextDim)
            Icon(icon, null, Modifier.size(16.dp), tint = JbColors.Accent)
            Spacer(Modifier.width(6.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    if (open) content()
}

@Composable
private fun FolderHeader(name: String, lost: Boolean, open: Boolean, count: Int, onToggle: () -> Unit, onReconnect: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !lost, onClick = onToggle).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = JbColors.TextDim)
        Icon(if (lost) Icons.Default.FolderOff else Icons.Default.Folder, null, Modifier.size(18.dp), tint = if (lost) JbColors.TextDim else JbColors.Accent)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (lost) JbColors.TextDim else JbColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (lost) stringResource(R.string.folder_lost) else stringResource(R.string.files_count, count),
                fontSize = 10.sp, color = JbColors.TextDim,
            )
        }
        if (lost) TextButton(onClick = onReconnect) { Text(stringResource(R.string.reconnect), fontSize = 12.sp) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remove_folder)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun BrowserRow(
    item: BrowserItem,
    indent: Int,
    selected: Boolean,
    favourites: Set<String>,
    onTap: () -> Unit,
    onPreview: () -> Unit,
    onFavourite: () -> Unit,
    onAddChannel: () -> Unit,
    onAddPlaylist: () -> Unit,
    onDragStart: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val file = (item as? BrowserItem.File)?.sample
    val isFav = file != null && file.key2 in favourites
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (selected) JbColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .browserDragSource(item, item.label, onDragStart)
            .clickable(onClick = onTap)
            .padding(start = (8 + indent * 12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(categoryColor(item).copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Text(categoryGlyph(item), fontSize = 10.sp, fontWeight = FontWeight.Black, color = categoryColor(item)) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(file?.bpm?.let { "$it BPM" }, file?.key, file?.size?.let(::sizeText)).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 10.sp, color = JbColors.TextDim, maxLines = 1)
        }
        if (selected) {
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.PlayArrow, stringResource(R.string.preview), tint = JbColors.Accent) }
        }
        if (file != null) {
            IconButton(onClick = onFavourite, modifier = Modifier.size(36.dp)) {
                Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.favourite), tint = if (isFav) JbColors.Accent else JbColors.TextDim)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.add_to_rack)) }, onClick = { menu = false; onAddChannel() })
                if (item !is BrowserItem.Sound) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_to_playlist)) }, onClick = { menu = false; onAddPlaylist() })
                }
                if (file != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isFav) R.string.unfavourite else R.string.favourite)) },
                        onClick = { menu = false; onFavourite() },
                    )
                    DropdownMenuItem(text = { Text(file.path, fontSize = 11.sp, color = JbColors.TextDim) }, onClick = { menu = false }, enabled = false)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = JbColors.TextDim, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

@Composable
private fun builtInSounds(): List<BrowserItem.Sound> {
    val drums = DrumSound.entries.map { BrowserItem.Sound(stringResource(it.label()).substringBefore(" ("), Instrument.Drum(it)) }
    return drums + BrowserItem.Sound("808", Instrument.Bass808()) + BrowserItem.Sound(stringResource(R.string.instrument_synth), Instrument.Synth())
}

private fun SampleCategory.label(): Int = when (this) {
    SampleCategory.DRUMS -> R.string.cat_drums
    SampleCategory.LOOPS -> R.string.cat_loops
    SampleCategory.ONE_SHOTS -> R.string.cat_one_shots
    SampleCategory.INSTRUMENTS -> R.string.cat_instruments
    SampleCategory.VOCALS -> R.string.cat_vocals
    SampleCategory.FX -> R.string.cat_fx
}

private fun categoryOf(item: BrowserItem): SampleCategory? = when (item) {
    is BrowserItem.File -> item.sample.category
    is BrowserItem.Sound -> if (item.instrument is Instrument.Drum) SampleCategory.DRUMS else SampleCategory.INSTRUMENTS
    is BrowserItem.ProjectSample -> null
}

private fun categoryGlyph(item: BrowserItem): String = when (categoryOf(item)) {
    SampleCategory.DRUMS -> "DR"
    SampleCategory.LOOPS -> "LP"
    SampleCategory.ONE_SHOTS -> "1S"
    SampleCategory.INSTRUMENTS -> "IN"
    SampleCategory.VOCALS -> "VO"
    SampleCategory.FX -> "FX"
    null -> "♪"
}

private fun categoryColor(item: BrowserItem): Color = when (categoryOf(item)) {
    SampleCategory.DRUMS -> JbColors.channel(0)
    SampleCategory.LOOPS -> JbColors.channel(2)
    SampleCategory.ONE_SHOTS -> JbColors.channel(4)
    SampleCategory.INSTRUMENTS -> JbColors.channel(6)
    SampleCategory.VOCALS -> JbColors.channel(8)
    SampleCategory.FX -> JbColors.channel(10)
    null -> JbColors.Accent
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

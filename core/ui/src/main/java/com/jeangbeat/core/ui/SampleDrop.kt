package com.jeangbeat.core.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext

/*
 * Browser drag & drop shared by every screen. The feature modules only see
 * [Dropped]; the app decides what the dragged thing is and what the drop does.
 */

/** What arrived at a drop target. */
sealed interface Dropped {
    /** Dragged from the in-app Browser. [item] is the app's own object. */
    data class Internal(val item: Any) : Dropped

    /** Audio files dragged from another app (e.g. Files in split screen). Read access is already granted. */
    data class External(val uris: List<Uri>) : Dropped
}

/** Local state carried by in-app drags. */
private class InAppDrag(val item: Any)

/**
 * Makes this element draggable after a long press. [onStart] runs when the drag
 * begins (e.g. to slide a drawer out of the way).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.browserDragSource(item: Any, label: String, onStart: () -> Unit = {}): Modifier {
    val current by rememberUpdatedState(item)
    val start by rememberUpdatedState(onStart)
    return dragAndDropSource { _ ->
        start()
        DragAndDropTransferData(ClipData.newPlainText(label, label), localState = InAppDrag(current))
    }
}

/**
 * Accepts Browser items and audio files from other apps. [onDrop] gets the
 * position inside this element and returns true if it used the drop. While a
 * drag hovers over the element it gets a gold outline.
 */
@Composable
fun Modifier.sampleDropTarget(enabled: Boolean = true, onDrop: (Dropped, Offset) -> Boolean): Modifier {
    if (!enabled) return this
    val context = LocalContext.current
    val drop by rememberUpdatedState(onDrop)
    var hovered by remember { mutableStateOf(false) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovered = true }
            override fun onExited(event: DragAndDropEvent) { hovered = false }
            override fun onEnded(event: DragAndDropEvent) { hovered = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovered = false
                val dragEvent = event.toAndroidDragEvent()
                // Drag events are in the Compose root view's coordinates.
                val local = coords?.let { Offset(dragEvent.x, dragEvent.y) - it.positionInRoot() } ?: Offset.Zero
                val inApp = dragEvent.localState as? InAppDrag
                if (inApp != null) return drop(Dropped.Internal(inApp.item), local)
                val clip = dragEvent.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                // Other apps' files need a permission that lasts while we copy them.
                // (null when the sending app granted access already or the URIs are public).
                runCatching { context.findActivity()?.requestDragAndDropPermissions(dragEvent) }
                return drop(Dropped.External(uris), local)
            }
        }
    }
    return this
        .onGloballyPositioned { coords = it }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { e ->
                e.toAndroidDragEvent().localState is InAppDrag || e.mimeTypes().any { it.startsWith("audio/") || it == "application/ogg" }
            },
            target = target,
        )
        .drawWithContent {
            drawContent()
            if (hovered) {
                drawRoundRect(JbColors.Accent.copy(alpha = 0.18f), cornerRadius = CornerRadius(8f))
                drawRoundRect(JbColors.Accent, style = Stroke(width = 2f * density), cornerRadius = CornerRadius(8f))
            }
        }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

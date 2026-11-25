package com.jeangbeat.app.browser

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Plays a Browser file once; starting another preview stops the previous one. */
class SamplePreviewer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(uri: Uri) {
        stop()
        val p = MediaPlayer()
        player = p
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(context, uri)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { if (player === it) stop() }
            p.setOnErrorListener { mp, _, _ -> if (player === mp) stop(); true }
            p.prepareAsync()
        }.onFailure { stop() }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}

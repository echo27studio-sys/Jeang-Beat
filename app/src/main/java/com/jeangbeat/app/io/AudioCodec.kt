package com.jeangbeat.app.io

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.jeangbeat.core.data.AudioData
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Decodes and encodes audio with the phone's built-in MediaCodec (no extra libraries). */
object AudioCodec {
    private const val TIMEOUT_US = 10_000L
    const val MAX_IMPORT_SECONDS = 10 * 60

    /**
     * Decodes any audio file Android supports (MP3, WAV, AAC/M4A, OGG, FLAC…)
     * to float PCM, downmixed to at most 2 channels.
     */
    fun decode(context: Context, uri: Uri): AudioData {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val out = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            val buf = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer()
                                while (fb.hasRemaining()) out.add(fb.get())
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.hasRemaining()) out.add(sb.get() / 32768f)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (channels > 0 && sampleRate > 0 && out.size / channels / sampleRate > MAX_IMPORT_SECONDS) {
                                throw IOException("Audio longer than $MAX_IMPORT_SECONDS seconds")
                            }
                        }
                    }
                }
            } finally {
                decoder.stop()
                decoder.release()
            }
            return downmix(out.toArray(), channels.coerceAtLeast(1), sampleRate)
        } finally {
            extractor.release()
        }
    }

    private fun downmix(data: FloatArray, channels: Int, sampleRate: Int): AudioData {
        if (channels <= 2) return AudioData(data, channels, sampleRate)
        val frames = data.size / channels
        val outData = FloatArray(frames * 2)
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            for (c in 0 until channels) {
                val v = data[f * channels + c]
                if (c % 2 == 0) l += v else r += v
            }
            outData[f * 2] = l / ((channels + 1) / 2)
            outData[f * 2 + 1] = r / (channels / 2)
        }
        return AudioData(outData, 2, sampleRate)
    }

    /**
     * Streams stereo float PCM into an AAC (.m4a) file. Call [write] with chunks, then [finish].
     */
    class AacWriter(fd: FileDescriptor, private val sampleRate: Int, bitrate: Int = 256_000) {
        private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val codec: MediaCodec
        private var trackIndex = -1
        private var muxerStarted = false
        private var presentationFrames = 0L
        private val info = MediaCodec.BufferInfo()

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun write(samples: FloatArray, frames: Int) {
            var offset = 0
            while (offset < frames) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!.order(ByteOrder.nativeOrder())
                    buf.clear()
                    val n = minOf(frames - offset, buf.capacity() / 4)
                    for (i in 0 until n * 2) buf.putShort((samples[offset * 2 + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                    val pts = presentationFrames * 1_000_000L / sampleRate
                    codec.queueInputBuffer(inIndex, 0, n * 4, pts, 0)
                    presentationFrames += n
                    offset += n
                }
                drain(false)
            }
        }

        fun finish() {
            while (true) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, presentationFrames * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }

        private fun drain(endOfStream: Boolean) {
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}

/** Growable float array without boxing. */
private class FloatList {
    private var data = FloatArray(1 shl 16)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

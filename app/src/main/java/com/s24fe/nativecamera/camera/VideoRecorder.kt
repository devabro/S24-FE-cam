package com.s24fe.nativecamera.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Video recording without MediaRecorder, because MediaRecorder cannot express
 * a 10-bit HLG profile. Standard mode = AVC (H.264) 8-bit. LOG mode = HEVC
 * Main10 with BT.2020 + HLG transfer — a flat, grade-friendly HDR signal.
 *
 * This is NOT sensor-level S-Log (Samsung does not expose that to third-party
 * apps). It is the closest a third-party app can get: 10-bit + HLG + a LUT
 * applied later in editing.
 */
class VideoRecorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val useHlg: Boolean
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    @Volatile private var draining = false
    private var drainThread: Thread? = null

    /**
     * The Surface handed to the camera session as a recording target.
     * Cached on purpose: MediaCodec.createInputSurface() must only be called
     * once, but the session builder asks for the surface more than once.
     */
    private var cachedInputSurface: Surface? = null
    val inputSurface: Surface
        get() = cachedInputSurface
            ?: throw IllegalStateException("VideoRecorder not prepared")

    fun prepare() {
        val mime = if (useHlg) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (useHlg) 25_000_000 else 17_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (useHlg) {
                // HEVC Main10 + BT.2020 + limited range + HLG transfer = HLG10.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
        }
        codec = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            cachedInputSurface = createInputSurface()
        }
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun start() {
        codec?.start()
        draining = true
        drainThread = Thread(::drainLoop, "VideoDrain").also { it.start() }
    }

    fun stop() {
        draining = false
        try {
            drainThread?.join(2000)
        } catch (ignored: InterruptedException) {
        }
        drainRemaining()
        try {
            codec?.stop()
        } catch (ignored: Exception) {
        }
        try {
            codec?.release()
        } catch (ignored: Exception) {
        }
        try {
            if (muxerStarted) muxer?.stop()
        } catch (ignored: Exception) {
        }
        try {
            muxer?.release()
        } catch (ignored: Exception) {
        }
        codec = null
        muxer = null
        cachedInputSurface = null
        trackIndex = -1
        muxerStarted = false
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (draining) {
            val outIndex = codec?.dequeueOutputBuffer(info, 10_000) ?: break
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec?.outputFormat ?: continue
                    trackIndex = muxer?.addTrack(newFormat) ?: -1
                    muxer?.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val buffer = codec?.getOutputBuffer(outIndex) ?: continue
                    if (muxerStarted && info.size > 0) {
                        // Skip codec-config buffers: they carry no frames.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer?.writeSampleData(trackIndex, buffer, info)
                        }
                    }
                    codec?.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    /** One last drain after the loop stops so the file tail is not lost. */
    private fun drainRemaining() {
        val info = MediaCodec.BufferInfo()
        try {
            codec?.signalEndOfInputStream()
        } catch (ignored: Exception) {
        }
        var guard = 0
        while (guard++ < 100) {
            val outIndex = codec?.dequeueOutputBuffer(info, 10_000) ?: break
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer?.addTrack(codec?.outputFormat ?: break) ?: break
                    muxer?.start()
                    muxerStarted = true
                }
            } else if (outIndex >= 0) {
                val buffer = codec?.getOutputBuffer(outIndex) ?: break
                if (muxerStarted && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    muxer?.writeSampleData(trackIndex, buffer, info)
                }
                codec?.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else {
                break
            }
        }
    }

    companion object {
        /** Kept as a small helper so callers never pass a null buffer around. */
        @Suppress("unused")
        fun copyBuffer(buffer: ByteBuffer): ByteBuffer = buffer.duplicate()
    }
}

package com.voiceguard.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class DecodedAudio(val samples16kMono: ShortArray, val durationMs: Long)

/**
 * Mode B decoder: MediaExtractor + MediaCodec to PCM, then downmix to mono and
 * linear-resample to 16 kHz. Handles m4a/mp3/wav/amr/3gp/ogg as long as the
 * device has a decoder for the container.
 */
object FileAudioDecoder {
    private const val TAG = "FileDecoder"
    private const val OUT_RATE = 16000

    fun decode(context: Context, uri: android.net.Uri): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            require(track >= 0) { "No audio track found in this file" }
            extractor.selectTrack(track)
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val raw = mutableListOf<Short>()
                val info = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                val startedAt = System.currentTimeMillis()
                while (!sawOutputEos) {
                    if (System.currentTimeMillis() - startedAt > 120_000) {
                        throw IllegalStateException("Decoding timed out (file too long or corrupt)")
                    }
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            drainToShorts(buf, info, raw)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    }
                }
                Timber.tag(TAG).i("Decoded %d samples @%dHz x%dch", raw.size, srcRate, srcChannels)
                val mono = downmix(raw.toShortArray(), srcChannels)
                val out = resample(mono, srcRate, OUT_RATE)
                val durMs = if (OUT_RATE > 0) out.size * 1000L / OUT_RATE else 0L
                return DecodedAudio(out, durMs)
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
            }
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun drainToShorts(buf: ByteBuffer, info: MediaCodec.BufferInfo, out: MutableList<Short>) {
        val dup = buf.duplicate().order(ByteOrder.nativeOrder())
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        // Most decoders output 16-bit PCM; if the size is odd we drop the tail byte.
        while (dup.remaining() >= 2) out.add(dup.short)
    }

    private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += interleaved[f * channels + c]
            out[f] = (acc / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (input.isEmpty()) return input
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (input.size / ratio).roundToInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, input.size - 1)
            val frac = pos - idx
            val a = input[idx].toDouble()
            val b = input.getOrElse(idx + 1) { input[idx] }.toDouble()
            out[i] = (a + (b - a) * frac).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Split 16 kHz mono samples into 100 ms PcmChunks. */
    fun toChunks(samples: ShortArray, startMs: Long = System.currentTimeMillis()): List<PcmChunk> {
        val per = OUT_RATE / 10 // 1600 samples = 100 ms
        val out = ArrayList<PcmChunk>((samples.size / per) + 1)
        var i = 0
        var t = startMs
        while (i < samples.size) {
            val end = minOf(i + per, samples.size)
            val bytes = ByteArray((end - i) * 2)
            for (j in i until end) {
                val s = samples[j].toInt()
                bytes[(j - i) * 2] = (s and 0xFF).toByte()
                bytes[(j - i) * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.add(PcmChunk(bytes, t))
            t += 100
            i = end
        }
        return out
    }
}


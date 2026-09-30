package com.voiceguard.audio

/** One 100 ms chunk of 16 kHz mono PCM16 little-endian audio (3200 bytes nominal). */
data class PcmChunk(val bytes: ByteArray, val timestampMs: Long) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmChunk) return false
        return timestampMs == other.timestampMs && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + timestampMs.hashCode()
}


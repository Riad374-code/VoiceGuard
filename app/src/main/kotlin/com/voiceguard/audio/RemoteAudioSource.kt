package com.voiceguard.audio

import kotlinx.coroutines.flow.Flow

/**
 * Mode C – IN-APP VOIP stub (feature flag `voipEnabled`, default OFF).
 *
 * A future WebRTC/SIP integration provides the remote call audio by implementing
 * this interface: decode the remote stream to 16 kHz mono PCM16 and emit 100 ms
 * [PcmChunk]s. The pipeline consumes it exactly like microphone or file audio,
 * so no other changes are needed. See README for integration notes.
 */
interface RemoteAudioSource {
    /** Cold flow of decoded PCM chunks; cancelled when the session stops. */
    fun chunks(): Flow<PcmChunk>

    /** Release sockets/decoders held by the implementation. */
    fun close()
}


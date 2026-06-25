package com.guardvoice.call

internal object CallAudioStream {
    fun accept(sessionId: String, chunk: ByteArray) {
        // Future AI pipeline attaches here. This MVP intentionally does not persist audio.
    }
}

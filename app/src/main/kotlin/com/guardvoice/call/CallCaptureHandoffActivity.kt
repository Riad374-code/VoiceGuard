package com.guardvoice.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.guardvoice.data.CallSessionRepository

class CallCaptureHandoffActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var didStartCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContentView(
            View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (didStartCapture) {
            return
        }
        didStartCapture = true
        window.decorView.post {
            startAudioCapture()
            mainHandler.postDelayed({ finish() }, FINISH_DELAY_MS)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startAudioCapture() {
        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        try {
            AudioCaptureService.start(this, phoneNumber, sessionId)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not hand off to microphone capture service.", exception)
            CallSessionRepository.markFailed(
                this,
                sessionId,
                "Microphone tracking could not start from the call popup."
            )
            AudioCaptureService.publishCaptureState(
                this,
                AudioCaptureService.CaptureState.Failed
            )
        }
    }

    companion object {
        private const val TAG = "CallCaptureHandoff"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val FINISH_DELAY_MS = 300L

        fun start(context: Context, phoneNumber: String, sessionId: String) {
            val intent = Intent(context, CallCaptureHandoffActivity::class.java)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            context.startActivity(intent)
        }
    }
}

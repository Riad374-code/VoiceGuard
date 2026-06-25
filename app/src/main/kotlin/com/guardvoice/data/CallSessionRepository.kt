package com.guardvoice.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

object CallSessionRepository {
    private val lock = Any()
    private val sessionState = MutableStateFlow<List<CallSession>>(emptyList())
    private var isLoaded = false

    fun observe(context: Context): StateFlow<List<CallSession>> {
        ensureLoaded(context.applicationContext)
        return sessionState.asStateFlow()
    }

    fun recordDetected(
        context: Context,
        phoneNumber: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val session = CallSession(
            id = UUID.randomUUID().toString(),
            phoneNumber = phoneNumber,
            startedAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            status = CallSessionStatus.Detected,
            verdict = CallVerdict.Pending,
            riskScore = 0,
            audioBytesStreamed = 0L,
            audioChunksStreamed = 0,
            transcriptPreview = "",
            summary = "Waiting for per-call consent.",
            reasons = emptyList()
        )
        replaceSessions(context) { sessions ->
            listOf(session) + sessions.take(MAX_STORED_SESSIONS - 1)
        }
        return session.id
    }

    fun markListening(context: Context, sessionId: String) {
        updateSession(context, sessionId) { session, nowMillis ->
            session.copy(
                updatedAtMillis = nowMillis,
                status = CallSessionStatus.Listening,
                summary = "Speaker mode and microphone stream are active."
            )
        }
    }

    fun recordAudioProgress(
        context: Context,
        sessionId: String,
        byteCount: Long,
        chunkCount: Int
    ) {
        if (byteCount <= 0L || chunkCount <= 0) {
            return
        }
        updateSession(context, sessionId) { session, nowMillis ->
            session.copy(
                updatedAtMillis = nowMillis,
                audioBytesStreamed = session.audioBytesStreamed + byteCount,
                audioChunksStreamed = session.audioChunksStreamed + chunkCount
            )
        }
    }

    fun markCompleted(context: Context, sessionId: String) {
        updateSession(context, sessionId) { session, nowMillis ->
            session.copy(
                updatedAtMillis = nowMillis,
                status = CallSessionStatus.Completed,
                summary = "Audio stream ended. AI analysis is not connected yet."
            )
        }
    }

    fun markDeclinedIfWaiting(context: Context, sessionId: String) {
        updateSession(context, sessionId) { session, nowMillis ->
            if (session.status != CallSessionStatus.Detected) {
                session
            } else {
                session.copy(
                    updatedAtMillis = nowMillis,
                    status = CallSessionStatus.Declined,
                    summary = "Tracking was not started for this call."
                )
            }
        }
    }

    fun markFailed(context: Context, sessionId: String, reason: String) {
        updateSession(context, sessionId) { session, nowMillis ->
            session.copy(
                updatedAtMillis = nowMillis,
                status = CallSessionStatus.Failed,
                summary = reason,
                reasons = listOf(reason)
            )
        }
    }

    fun saveAnalysis(
        context: Context,
        sessionId: String,
        verdict: CallVerdict,
        riskScore: Int,
        transcriptPreview: String,
        summary: String,
        reasons: List<String>
    ) {
        updateSession(context, sessionId) { session, nowMillis ->
            session.copy(
                updatedAtMillis = nowMillis,
                verdict = verdict,
                riskScore = riskScore.coerceIn(MIN_RISK_SCORE, MAX_RISK_SCORE),
                transcriptPreview = transcriptPreview,
                summary = summary,
                reasons = reasons
            )
        }
    }

    private fun updateSession(
        context: Context,
        sessionId: String,
        transform: (CallSession, Long) -> CallSession
    ) {
        if (sessionId.isBlank()) {
            return
        }
        replaceSessions(context) { sessions ->
            val nowMillis = System.currentTimeMillis()
            sessions.map { session ->
                if (session.id == sessionId) transform(session, nowMillis) else session
            }
        }
    }

    private fun replaceSessions(
        context: Context,
        transform: (List<CallSession>) -> List<CallSession>
    ) {
        synchronized(lock) {
            ensureLoaded(context.applicationContext)
            val updatedSessions = transform(sessionState.value)
            sessionState.value = updatedSessions
            preferences(context).edit()
                .putString(PREF_SESSIONS, encodeSessions(updatedSessions))
                .apply()
        }
    }

    private fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (isLoaded) {
                return
            }
            sessionState.value = decodeSessions(
                preferences(context).getString(PREF_SESSIONS, null)
            )
            isLoaded = true
        }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun encodeSessions(sessions: List<CallSession>): String {
        val jsonSessions = sessions.map { session ->
            JSONObject()
                .put(KEY_ID, session.id)
                .put(KEY_PHONE_NUMBER, session.phoneNumber)
                .put(KEY_STARTED_AT, session.startedAtMillis)
                .put(KEY_UPDATED_AT, session.updatedAtMillis)
                .put(KEY_STATUS, session.status.name)
                .put(KEY_VERDICT, session.verdict.name)
                .put(KEY_RISK_SCORE, session.riskScore)
                .put(KEY_AUDIO_BYTES, session.audioBytesStreamed)
                .put(KEY_AUDIO_CHUNKS, session.audioChunksStreamed)
                .put(KEY_TRANSCRIPT_PREVIEW, session.transcriptPreview)
                .put(KEY_SUMMARY, session.summary)
                .put(KEY_REASONS, JSONArray(session.reasons))
        }
        return JSONArray(jsonSessions).toString()
    }

    private fun decodeSessions(rawSessions: String?): List<CallSession> {
        if (rawSessions.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val jsonSessions = JSONArray(rawSessions)
            List(jsonSessions.length()) { index ->
                decodeSession(jsonSessions.getJSONObject(index))
            }
        } catch (exception: JSONException) {
            Log.e(TAG, "Stored call session history is malformed.", exception)
            emptyList()
        }
    }

    private fun decodeSession(jsonSession: JSONObject): CallSession =
        CallSession(
            id = jsonSession.optString(KEY_ID),
            phoneNumber = jsonSession.optString(KEY_PHONE_NUMBER),
            startedAtMillis = jsonSession.optLong(KEY_STARTED_AT),
            updatedAtMillis = jsonSession.optLong(KEY_UPDATED_AT),
            status = enumValueOrDefault(
                jsonSession.optString(KEY_STATUS),
                CallSessionStatus.Completed
            ),
            verdict = enumValueOrDefault(
                jsonSession.optString(KEY_VERDICT),
                CallVerdict.Pending
            ),
            riskScore = jsonSession.optInt(KEY_RISK_SCORE),
            audioBytesStreamed = jsonSession.optLong(KEY_AUDIO_BYTES),
            audioChunksStreamed = jsonSession.optInt(KEY_AUDIO_CHUNKS),
            transcriptPreview = jsonSession.optString(KEY_TRANSCRIPT_PREVIEW),
            summary = jsonSession.optString(KEY_SUMMARY),
            reasons = decodeReasons(jsonSession.optJSONArray(KEY_REASONS))
        )

    private fun decodeReasons(jsonReasons: JSONArray?): List<String> {
        if (jsonReasons == null) {
            return emptyList()
        }
        return List(jsonReasons.length()) { index -> jsonReasons.optString(index) }
            .filter { reason -> reason.isNotBlank() }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String,
        fallback: T
    ): T =
        enumValues<T>().firstOrNull { enumValue -> enumValue.name == value } ?: fallback

    private const val TAG = "CallSessionRepository"
    private const val PREF_NAME = "guardvoice_call_sessions"
    private const val PREF_SESSIONS = "sessions"
    private const val MAX_STORED_SESSIONS = 50
    private const val MIN_RISK_SCORE = 0
    private const val MAX_RISK_SCORE = 100
    private const val KEY_ID = "id"
    private const val KEY_PHONE_NUMBER = "phoneNumber"
    private const val KEY_STARTED_AT = "startedAtMillis"
    private const val KEY_UPDATED_AT = "updatedAtMillis"
    private const val KEY_STATUS = "status"
    private const val KEY_VERDICT = "verdict"
    private const val KEY_RISK_SCORE = "riskScore"
    private const val KEY_AUDIO_BYTES = "audioBytesStreamed"
    private const val KEY_AUDIO_CHUNKS = "audioChunksStreamed"
    private const val KEY_TRANSCRIPT_PREVIEW = "transcriptPreview"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_REASONS = "reasons"
}

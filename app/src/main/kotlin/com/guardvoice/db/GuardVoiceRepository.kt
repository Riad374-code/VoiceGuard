package com.guardvoice.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.guardvoice.data.CallSession
import com.guardvoice.data.CallSessionStatus
import com.guardvoice.data.CallVerdict
import com.guardvoice.db.CallRecordingsContract.RecordingsEntry
import com.guardvoice.db.DetectionsContract.DetectionsEntry
import com.guardvoice.db.DecisionsContract.DecisionsEntry
import com.guardvoice.db.PersonalInfoContract.PersonalInfoEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@Suppress("TooManyFunctions")
class GuardVoiceRepository private constructor(context: Context) {

    private val dbHelper = GuardVoiceDbHelper(context.applicationContext)

    // In-memory cache for call recordings to power reactive UI
    private val recordingsState = MutableStateFlow<List<CallSession>>(emptyList())
    private var isRecordingsLoaded = false

    // ========================
    // Call Recordings
    // ========================

    fun observeRecordings(): Flow<List<CallSession>> {
        if (!isRecordingsLoaded) {
            loadRecordings()
        }
        return recordingsState.asStateFlow()
    }

    fun getRecordings(): List<CallSession> {
        if (!isRecordingsLoaded) {
            loadRecordings()
        }
        return recordingsState.value
    }

    fun insertRecording(session: CallSession) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(RecordingsEntry.COLUMN_SESSION_ID, session.id)
            put(RecordingsEntry.COLUMN_PHONE_NUMBER, session.phoneNumber)
            put(RecordingsEntry.COLUMN_STARTED_AT, session.startedAtMillis)
            put(RecordingsEntry.COLUMN_UPDATED_AT, session.updatedAtMillis)
            put(RecordingsEntry.COLUMN_STATUS, session.status.name)
            put(RecordingsEntry.COLUMN_VERDICT, session.verdict.name)
            put(RecordingsEntry.COLUMN_RISK_SCORE, session.riskScore)
            put(RecordingsEntry.COLUMN_AUDIO_BYTES, session.audioBytesStreamed)
            put(RecordingsEntry.COLUMN_AUDIO_CHUNKS, session.audioChunksStreamed)
            put(RecordingsEntry.COLUMN_TRANSCRIPT_PREVIEW, session.transcriptPreview)
            put(RecordingsEntry.COLUMN_SUMMARY, session.summary)
            put(RecordingsEntry.COLUMN_REASONS, JSONArray(session.reasons).toString())
        }
        db.insertWithOnConflict(
            CallRecordingsContract.TABLE_NAME,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        enforceMaxRecordings()
        loadRecordings()
    }

    fun updateRecording(sessionId: String, transform: (CallSession) -> CallSession) {
        val existing = getRecordingBySessionId(sessionId) ?: return
        val updated = transform(existing)
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(RecordingsEntry.COLUMN_PHONE_NUMBER, updated.phoneNumber)
            put(RecordingsEntry.COLUMN_UPDATED_AT, updated.updatedAtMillis)
            put(RecordingsEntry.COLUMN_STATUS, updated.status.name)
            put(RecordingsEntry.COLUMN_VERDICT, updated.verdict.name)
            put(RecordingsEntry.COLUMN_RISK_SCORE, updated.riskScore)
            put(RecordingsEntry.COLUMN_AUDIO_BYTES, updated.audioBytesStreamed)
            put(RecordingsEntry.COLUMN_AUDIO_CHUNKS, updated.audioChunksStreamed)
            put(RecordingsEntry.COLUMN_TRANSCRIPT_PREVIEW, updated.transcriptPreview)
            put(RecordingsEntry.COLUMN_SUMMARY, updated.summary)
            put(RecordingsEntry.COLUMN_REASONS, JSONArray(updated.reasons).toString())
        }
        db.update(
            CallRecordingsContract.TABLE_NAME,
            values,
            "${RecordingsEntry.COLUMN_SESSION_ID} = ?",
            arrayOf(sessionId)
        )
        loadRecordings()
    }

    fun getRecordingBySessionId(sessionId: String): CallSession? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CallRecordingsContract.TABLE_NAME,
            null,
            "${RecordingsEntry.COLUMN_SESSION_ID} = ?",
            arrayOf(sessionId),
            null,
            null,
            null
        )
        return cursor.use { c ->
            if (c.moveToFirst()) {
                cursorToCallSession(c)
            } else null
        }
    }

    private fun loadRecordings() {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CallRecordingsContract.TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "${RecordingsEntry.COLUMN_STARTED_AT} DESC"
        )
        val list = mutableListOf<CallSession>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToCallSession(c))
            }
        }
        recordingsState.value = list
        isRecordingsLoaded = true
    }

    private fun enforceMaxRecordings() {
        val db = dbHelper.writableDatabase
        val max = CallRecordingsContract.MAX_STORED_RECORDINGS
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${CallRecordingsContract.TABLE_NAME}",
            null
        )
        val count = cursor.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        if (count > max) {
            val excess = count - max
            db.execSQL(
                """
                DELETE FROM ${CallRecordingsContract.TABLE_NAME}
                WHERE ${RecordingsEntry.COLUMN_ID} IN (
                    SELECT ${RecordingsEntry.COLUMN_ID} FROM ${CallRecordingsContract.TABLE_NAME}
                    ORDER BY ${RecordingsEntry.COLUMN_STARTED_AT} ASC
                    LIMIT $excess
                )
                """.trimIndent()
            )
        }
    }

    private fun cursorToCallSession(cursor: Cursor): CallSession =
        CallSession(
            id = cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_SESSION_ID)),
            phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_PHONE_NUMBER)),
            startedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_STARTED_AT)),
            updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_UPDATED_AT)),
            status = enumValueOrDefault(
                cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_STATUS)),
                CallSessionStatus.Detected
            ),
            verdict = enumValueOrDefault(
                cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_VERDICT)),
                CallVerdict.Pending
            ),
            riskScore = cursor.getInt(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_RISK_SCORE)),
            audioBytesStreamed = cursor.getLong(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_AUDIO_BYTES)),
            audioChunksStreamed = cursor.getInt(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_AUDIO_CHUNKS)),
            transcriptPreview = cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_TRANSCRIPT_PREVIEW)),
            summary = cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_SUMMARY)),
            reasons = decodeReasons(cursor.getString(cursor.getColumnIndexOrThrow(RecordingsEntry.COLUMN_REASONS)))
        )

    // ========================
    // Detections
    // ========================

    fun insertDetection(
        sessionId: String,
        transcript: String,
        verdict: CallVerdict,
        riskScore: Int,
        reasons: List<String>,
        chunkIndex: Int = 0
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DetectionsEntry.COLUMN_SESSION_ID, sessionId)
            put(DetectionsEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
            put(DetectionsEntry.COLUMN_TRANSCRIPT, transcript)
            put(DetectionsEntry.COLUMN_VERDICT, verdict.name)
            put(DetectionsEntry.COLUMN_RISK_SCORE, riskScore)
            put(DetectionsEntry.COLUMN_REASONS, JSONArray(reasons).toString())
            put(DetectionsEntry.COLUMN_CHUNK_INDEX, chunkIndex)
        }
        db.insert(DetectionsContract.TABLE_NAME, null, values)
    }

    fun getDetectionsForSession(sessionId: String): List<DetectionRecord> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DetectionsContract.TABLE_NAME,
            null,
            "${DetectionsEntry.COLUMN_SESSION_ID} = ?",
            arrayOf(sessionId),
            null,
            null,
            "${DetectionsEntry.COLUMN_TIMESTAMP} DESC"
        )
        val list = mutableListOf<DetectionRecord>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    DetectionRecord(
                        id = cursorToInt(c, DetectionsEntry.COLUMN_ID),
                        sessionId = cursorToString(c, DetectionsEntry.COLUMN_SESSION_ID),
                        timestamp = cursorToLong(c, DetectionsEntry.COLUMN_TIMESTAMP),
                        transcript = cursorToString(c, DetectionsEntry.COLUMN_TRANSCRIPT),
                        verdict = cursorToString(c, DetectionsEntry.COLUMN_VERDICT),
                        riskScore = cursorToInt(c, DetectionsEntry.COLUMN_RISK_SCORE),
                        reasons = decodeReasons(cursorToString(c, DetectionsEntry.COLUMN_REASONS)),
                        chunkIndex = cursorToInt(c, DetectionsEntry.COLUMN_CHUNK_INDEX)
                    )
                )
            }
        }
        return list
    }

    fun deleteDetectionsForSession(sessionId: String) {
        val db = dbHelper.writableDatabase
        db.delete(
            DetectionsContract.TABLE_NAME,
            "${DetectionsEntry.COLUMN_SESSION_ID} = ?",
            arrayOf(sessionId)
        )
    }

    // ========================
    // Decisions
    // ========================

    fun insertDecision(
        sessionId: String,
        phoneNumber: String,
        decision: String,
        reason: String = ""
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DecisionsEntry.COLUMN_SESSION_ID, sessionId)
            put(DecisionsEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
            put(DecisionsEntry.COLUMN_DECISION, decision)
            put(DecisionsEntry.COLUMN_REASON, reason)
            put(DecisionsEntry.COLUMN_PHONE_NUMBER, phoneNumber)
        }
        db.insert(DecisionsContract.TABLE_NAME, null, values)
    }

    fun getDecisions(): List<DecisionRecord> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DecisionsContract.TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "${DecisionsEntry.COLUMN_TIMESTAMP} DESC"
        )
        val list = mutableListOf<DecisionRecord>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    DecisionRecord(
                        id = cursorToInt(c, DecisionsEntry.COLUMN_ID),
                        sessionId = cursorToString(c, DecisionsEntry.COLUMN_SESSION_ID),
                        timestamp = cursorToLong(c, DecisionsEntry.COLUMN_TIMESTAMP),
                        decision = cursorToString(c, DecisionsEntry.COLUMN_DECISION),
                        reason = cursorToString(c, DecisionsEntry.COLUMN_REASON),
                        phoneNumber = cursorToString(c, DecisionsEntry.COLUMN_PHONE_NUMBER)
                    )
                )
            }
        }
        return list
    }

    // ========================
    // Personal Info
    // ========================

    fun setPersonalInfo(key: String, value: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(PersonalInfoEntry.COLUMN_KEY, key)
            put(PersonalInfoEntry.COLUMN_VALUE, value)
            put(PersonalInfoEntry.COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            PersonalInfoContract.TABLE_NAME,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getPersonalInfo(key: String): String? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            PersonalInfoContract.TABLE_NAME,
            arrayOf(PersonalInfoEntry.COLUMN_VALUE),
            "${PersonalInfoEntry.COLUMN_KEY} = ?",
            arrayOf(key),
            null,
            null,
            null
        )
        return cursor.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)
            } else null
        }
    }

    fun deletePersonalInfo(key: String) {
        val db = dbHelper.writableDatabase
        db.delete(
            PersonalInfoContract.TABLE_NAME,
            "${PersonalInfoEntry.COLUMN_KEY} = ?",
            arrayOf(key)
        )
    }

    // ========================
    // Helpers
    // ========================

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun decodeReasons(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.optString(index) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode reasons JSON", e)
            emptyList()
        }
    }

    private fun cursorToInt(cursor: Cursor, columnName: String): Int {
        return cursor.getInt(cursor.getColumnIndexOrThrow(columnName))
    }

    private fun cursorToLong(cursor: Cursor, columnName: String): Long {
        return cursor.getLong(cursor.getColumnIndexOrThrow(columnName))
    }

    private fun cursorToString(cursor: Cursor, columnName: String): String {
        return cursor.getString(cursor.getColumnIndexOrThrow(columnName))
    }

    // ========================
    // Singleton
    // ========================

    companion object {
        private const val TAG = "GuardVoiceRepository"
        @Volatile
        private var instance: GuardVoiceRepository? = null

        fun getInstance(context: Context): GuardVoiceRepository {
            return instance ?: synchronized(this) {
                instance ?: GuardVoiceRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

// ========================
// Data classes
// ========================

data class DetectionRecord(
    val id: Int,
    val sessionId: String,
    val timestamp: Long,
    val transcript: String,
    val verdict: String,
    val riskScore: Int,
    val reasons: List<String>,
    val chunkIndex: Int
)

data class DecisionRecord(
    val id: Int,
    val sessionId: String,
    val timestamp: Long,
    val decision: String,
    val reason: String,
    val phoneNumber: String
)

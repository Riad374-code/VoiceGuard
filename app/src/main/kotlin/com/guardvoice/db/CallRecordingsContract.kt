package com.guardvoice.db

import android.provider.BaseColumns

object CallRecordingsContract {
    const val TABLE_NAME = "call_recordings"

    object RecordingsEntry : BaseColumns {
        const val COLUMN_ID = "_id"
        const val COLUMN_SESSION_ID = "session_id"
        const val COLUMN_PHONE_NUMBER = "phone_number"
        const val COLUMN_STARTED_AT = "started_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_STATUS = "status"
        const val COLUMN_VERDICT = "verdict"
        const val COLUMN_RISK_SCORE = "risk_score"
        const val COLUMN_AUDIO_BYTES = "audio_bytes"
        const val COLUMN_AUDIO_CHUNKS = "audio_chunks"
        const val COLUMN_TRANSCRIPT_PREVIEW = "transcript_preview"
        const val COLUMN_SUMMARY = "summary"
        const val COLUMN_REASONS = "reasons"
    }

    const val MAX_STORED_RECORDINGS = 50

    val SQL_CREATE_RECORDINGS_ENTRIES = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            ${RecordingsEntry.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${RecordingsEntry.COLUMN_SESSION_ID} TEXT NOT NULL UNIQUE,
            ${RecordingsEntry.COLUMN_PHONE_NUMBER} TEXT NOT NULL DEFAULT '',
            ${RecordingsEntry.COLUMN_STARTED_AT} INTEGER NOT NULL,
            ${RecordingsEntry.COLUMN_UPDATED_AT} INTEGER NOT NULL,
            ${RecordingsEntry.COLUMN_STATUS} TEXT NOT NULL DEFAULT 'Detected',
            ${RecordingsEntry.COLUMN_VERDICT} TEXT NOT NULL DEFAULT 'Pending',
            ${RecordingsEntry.COLUMN_RISK_SCORE} INTEGER NOT NULL DEFAULT 0,
            ${RecordingsEntry.COLUMN_AUDIO_BYTES} INTEGER NOT NULL DEFAULT 0,
            ${RecordingsEntry.COLUMN_AUDIO_CHUNKS} INTEGER NOT NULL DEFAULT 0,
            ${RecordingsEntry.COLUMN_TRANSCRIPT_PREVIEW} TEXT NOT NULL DEFAULT '',
            ${RecordingsEntry.COLUMN_SUMMARY} TEXT NOT NULL DEFAULT '',
            ${RecordingsEntry.COLUMN_REASONS} TEXT NOT NULL DEFAULT ''
        )
    """.trimIndent()
}

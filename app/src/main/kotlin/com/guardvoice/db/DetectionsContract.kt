package com.guardvoice.db

import android.provider.BaseColumns

object DetectionsContract {
    const val TABLE_NAME = "detections"

    object DetectionsEntry : BaseColumns {
        const val COLUMN_ID = "_id"
        const val COLUMN_SESSION_ID = "session_id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_TRANSCRIPT = "transcript"
        const val COLUMN_VERDICT = "verdict"
        const val COLUMN_RISK_SCORE = "risk_score"
        const val COLUMN_REASONS = "reasons"
        const val COLUMN_CHUNK_INDEX = "chunk_index"
    }

    val SQL_CREATE_DETECTIONS_ENTRIES = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            ${DetectionsEntry.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${DetectionsEntry.COLUMN_SESSION_ID} TEXT NOT NULL,
            ${DetectionsEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL,
            ${DetectionsEntry.COLUMN_TRANSCRIPT} TEXT NOT NULL DEFAULT '',
            ${DetectionsEntry.COLUMN_VERDICT} TEXT NOT NULL DEFAULT 'Pending',
            ${DetectionsEntry.COLUMN_RISK_SCORE} INTEGER NOT NULL DEFAULT 0,
            ${DetectionsEntry.COLUMN_REASONS} TEXT NOT NULL DEFAULT '',
            ${DetectionsEntry.COLUMN_CHUNK_INDEX} INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()
}

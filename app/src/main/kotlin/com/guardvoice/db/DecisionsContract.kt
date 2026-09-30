package com.guardvoice.db

import android.provider.BaseColumns

object DecisionsContract {
    const val TABLE_NAME = "decisions"

    object DecisionsEntry : BaseColumns {
        const val COLUMN_ID = "_id"
        const val COLUMN_SESSION_ID = "session_id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_DECISION = "decision"
        const val COLUMN_REASON = "reason"
        const val COLUMN_PHONE_NUMBER = "phone_number"
    }

    val SQL_CREATE_DECISIONS_ENTRIES = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            ${DecisionsEntry.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${DecisionsEntry.COLUMN_SESSION_ID} TEXT NOT NULL,
            ${DecisionsEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL,
            ${DecisionsEntry.COLUMN_DECISION} TEXT NOT NULL,
            ${DecisionsEntry.COLUMN_REASON} TEXT NOT NULL DEFAULT '',
            ${DecisionsEntry.COLUMN_PHONE_NUMBER} TEXT NOT NULL DEFAULT ''
        )
    """.trimIndent()
}

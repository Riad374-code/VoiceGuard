package com.guardvoice.db

import android.provider.BaseColumns

object PersonalInfoContract {
    const val TABLE_NAME = "personal_info"

    object PersonalInfoEntry : BaseColumns {
        const val COLUMN_ID = "_id"
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"
        const val COLUMN_UPDATED_AT = "updated_at"
    }

    val SQL_CREATE_PERSONAL_INFO_ENTRIES = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            ${PersonalInfoEntry.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${PersonalInfoEntry.COLUMN_KEY} TEXT NOT NULL UNIQUE,
            ${PersonalInfoEntry.COLUMN_VALUE} TEXT NOT NULL DEFAULT '',
            ${PersonalInfoEntry.COLUMN_UPDATED_AT} INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()
}

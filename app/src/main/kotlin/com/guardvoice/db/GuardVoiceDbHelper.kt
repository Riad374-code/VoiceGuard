package com.guardvoice.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.guardvoice.db.CallRecordingsContract.SQL_CREATE_RECORDINGS_ENTRIES
import com.guardvoice.db.DetectionsContract.SQL_CREATE_DETECTIONS_ENTRIES
import com.guardvoice.db.DecisionsContract.SQL_CREATE_DECISIONS_ENTRIES
import com.guardvoice.db.PersonalInfoContract.SQL_CREATE_PERSONAL_INFO_ENTRIES

class GuardVoiceDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_RECORDINGS_ENTRIES)
        db.execSQL(SQL_CREATE_DETECTIONS_ENTRIES)
        db.execSQL(SQL_CREATE_DECISIONS_ENTRIES)
        db.execSQL(SQL_CREATE_PERSONAL_INFO_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // For v1 to v2, drop and recreate all tables (destructive but acceptable for initial deploy)
        db.execSQL("DROP TABLE IF EXISTS ${CallRecordingsContract.TABLE_NAME}")
        db.execSQL("DROP TABLE IF EXISTS ${DetectionsContract.TABLE_NAME}")
        db.execSQL("DROP TABLE IF EXISTS ${DecisionsContract.TABLE_NAME}")
        db.execSQL("DROP TABLE IF EXISTS ${PersonalInfoContract.TABLE_NAME}")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        const val DATABASE_NAME = "guardvoice.db"
        const val DATABASE_VERSION = 1
    }
}

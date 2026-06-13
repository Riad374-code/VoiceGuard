package com.guardvoice.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class ContactsHelper(private val context: Context) {
    fun isSavedNumber(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) {
            return false
        }

        if (!context.hasReadContactsPermission()) {
            return false
        }

        return context.contentResolver.hasPhoneLookupResult(phoneNumber)
    }
}

private fun Context.hasReadContactsPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

private fun ContentResolver.hasPhoneLookupResult(phoneNumber: String): Boolean {
    val lookupUri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(phoneNumber)
    )
    val projection = arrayOf(ContactsContract.PhoneLookup._ID)

    return try {
        query(lookupUri, projection, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    } catch (_: SecurityException) {
        false
    }
}

package com.foxtrader.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers

/**
 * Debug-only LeakCanary policy for a known Android-vendor framework leak.
 * Release builds do not include this provider.
 */
class LeakCanaryOemFilterProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val resourcesImplAppContext = AndroidReferenceMatchers.staticFieldLeak(
            "android.content.res.ResourcesImpl",
            "mAppContext",
        )
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = LeakCanary.config.referenceMatchers + resourcesImplAppContext,
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

package com.foxtrader.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.LeakCanary
import shark.ReferencePattern
import shark.ignored

/**
 * Debug-only LeakCanary policy for a known Android-vendor framework leak.
 *
 * Some Android forks add ResourcesImpl#mAppContext as a static field. A destroyed
 * WorkManager SystemJobService can then remain reachable through that framework
 * field even though FoxTrader no longer owns the service. LeakCanary correctly
 * classifies the trace as a Library Leak; treating that exact vendor reference
 * as ignored keeps debug leak reports focused on application-owned leaks.
 *
 * This provider exists only in src/debug. Release builds contain neither this
 * policy nor LeakCanary.
 */
class LeakCanaryOemFilterProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val resourcesImplAppContext = ReferencePattern.staticField(
            className = "android.content.res.ResourcesImpl",
            fieldName = "mAppContext",
        )
        val current = LeakCanary.config.referenceMatchers
            .filterNot { matcher -> matcher.pattern == resourcesImplAppContext }
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = current + resourcesImplAppContext.ignored(),
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

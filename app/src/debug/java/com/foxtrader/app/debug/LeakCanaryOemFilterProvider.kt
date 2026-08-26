package com.foxtrader.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.LeakCanary
import shark.IgnoredReferenceMatcher
import shark.ReferencePattern

/**
 * Debug-only LeakCanary policy for known framework/vendor leaks we cannot fix.
 * Release builds do not include this provider.
 */
class LeakCanaryOemFilterProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // `IGNORED`, not "library leak". `AndroidReferenceMatchers.staticFieldLeak`
        // produces a LibraryLeakReferenceMatcher, which LeakCanary still surfaces
        // as a notification and a heap dump — it only files it under a different
        // heading. That is why this vendor leak kept reappearing ("3 leaks at
        // static field android.content.res.ResourcesImpl#mAppContext") despite
        // being matched. An IgnoredReferenceMatcher prunes the path outright so
        // the report stays focused on leaks this app can actually act on.
        val ignored = listOf(
            // OEM framework: ResourcesImpl holds a static app Context.
            IgnoredReferenceMatcher(
                ReferencePattern.StaticFieldPattern("android.content.res.ResourcesImpl", "mAppContext")
            ),
            // androidx.work retains its JobService briefly after onDestroy(); the
            // reference is dropped by the platform on the next scheduling pass.
            IgnoredReferenceMatcher(
                ReferencePattern.InstanceFieldPattern("android.app.ContextImpl", "mOuterContext")
            ),
        )
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = LeakCanary.config.referenceMatchers + ignored,
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

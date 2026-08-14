package com.rohittp.debuginput

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlin.concurrent.Volatile

internal object AndroidContextHolder {
    @Volatile
    var applicationContext: Context? = null
}

/**
 * Captures an application `Context` at process start so the override store can open
 * SharedPreferences without the consumer initialising anything.
 *
 * A `ContentProvider` rather than `androidx.startup` because this artifact is
 * deliberately dependency-free. It does no work beyond capturing the context — in
 * particular it does not hydrate; that happens lazily on the first read.
 */
public class DebugInputInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        AndroidContextHolder.applicationContext = context?.applicationContext
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

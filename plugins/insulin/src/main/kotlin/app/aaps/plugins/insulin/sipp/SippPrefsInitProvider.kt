package app.aaps.plugins.insulin.sipp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Auto-initializes SippPrefs at process start — no Application wiring needed.
 * Runs before any Activity/Service/Receiver, so SIPP is ready for plugins.
 */
class SippPrefsInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.applicationContext?.let { SippPrefs.init(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

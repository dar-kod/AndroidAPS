package app.aaps.plugins.insulin.sipp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Plugin-local prefs for SIPP. No core keys, no MainApp.
 * Call SippPrefs.init(context) once before reading.
 * Defaults are ON to keep SIPP "no-knobs" by default.
 */
object SippPrefs {

    @Volatile private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    private const val K_ENABLE_PK = "sipp_enable_pk"
    private const val K_ENABLE_ISF = "sipp_enable_isf"
    private const val K_ALLOW_DIA_ABOVE_9H = "sipp_allow_dia_above_9h"

    private const val K_SITE_LOCATION = "sipp_site_location"  // "ABDOMEN" | "ARM" | "THIGH"
    private const val K_SITE_AGE_ENABLED = "sipp_site_age_enabled"
    private const val K_SITE_AGE_H = "sipp_site_age_h"        // string hours

    // Feature toggles (defaults ON)
    fun enablePk(): Boolean = sp?.getBoolean(K_ENABLE_PK, true) ?: true
    fun setEnablePk(v: Boolean) {
        sp?.edit { putBoolean(K_ENABLE_PK, v) }
    }

    fun enableIsf(): Boolean = sp?.getBoolean(K_ENABLE_ISF, true) ?: true
    fun setEnableIsf(v: Boolean) {
        sp?.edit { putBoolean(K_ENABLE_ISF, v) }
    }

    fun allowDiaAbove9h(): Boolean = sp?.getBoolean(K_ALLOW_DIA_ABOVE_9H, true) ?: true
    fun setAllowDiaAbove9h(v: Boolean) {
        sp?.edit { putBoolean(K_ALLOW_DIA_ABOVE_9H, v) }
    }

    // Optional site context
    fun siteLocation(): String = sp?.getString(K_SITE_LOCATION, "ABDOMEN") ?: "ABDOMEN"
    fun setSiteLocation(v: String) {
        sp?.edit { putString(K_SITE_LOCATION, v) }
    }

    fun siteAgeEnabled(): Boolean = sp?.getBoolean(K_SITE_AGE_ENABLED, false) ?: false
    fun setSiteAgeEnabled(v: Boolean) {
        sp?.edit { putBoolean(K_SITE_AGE_ENABLED, v) }
    }

    fun siteAgeH(): String = sp?.getString(K_SITE_AGE_H, "1") ?: "1"
    fun setSiteAgeH(v: String) {
        sp?.edit { putString(K_SITE_AGE_H, v) }
    }
}

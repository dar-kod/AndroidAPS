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

    // New canonical keys (uppercase SIPP)
    private const val K_ENABLE_PK = "SIPP_enable_pk"
    private const val K_ENABLE_ISF = "SIPP_enable_isf"
    private const val K_ALLOW_DIA_ABOVE_9H = "SIPP_allow_dia_above_9h"
    private const val K_SITE_LOCATION = "SIPP_site_location"   // "ABDOMEN" | "ARM" | "THIGH"
    private const val K_SITE_AGE_ENABLED = "SIPP_site_age_enabled"
    private const val K_SITE_AGE_H = "SIPP_site_age_h"      // string hours

    // Legacy keys (lowercase) kept only for migration
    private const val L_ENABLE_PK = "sipp_enable_pk"
    private const val L_ENABLE_ISF = "sipp_enable_isf"
    private const val L_ALLOW_DIA_ABOVE_9H = "sipp_allow_dia_above_9h"
    private const val L_SITE_LOCATION = "sipp_site_location"
    private const val L_SITE_AGE_ENABLED = "sipp_site_age_enabled"
    private const val L_SITE_AGE_H = "sipp_site_age_h"

    // Persisted live PK/PD state (unchanged)
    private const val K_STATE_DIA_H = "SIPP_state_dia_h"
    private const val K_STATE_TPEAK_MIN = "SIPP_state_tpeak_min"
    private const val K_STATE_ISF_MULT = "SIPP_state_isf_mult"
    private const val K_STATE_TS_MS = "SIPP_state_timestamp_ms"

    // --- migration helpers ---
    private fun getBoolMigrate(newK: String, oldK: String, def: Boolean): Boolean {
        val p = sp ?: return def
        if (p.contains(newK)) return p.getBoolean(newK, def)
        if (p.contains(oldK)) {
            val v = p.getBoolean(oldK, def)
            p.edit { putBoolean(newK, v); remove(oldK) }
            return v
        }
        return def
    }

    private fun setBool(newK: String, v: Boolean) {
        sp?.edit { putBoolean(newK, v) }
    }

    private fun getStringMigrate(newK: String, oldK: String, def: String): String {
        val p = sp ?: return def
        if (p.contains(newK)) return p.getString(newK, def) ?: def
        if (p.contains(oldK)) {
            val v = p.getString(oldK, def) ?: def
            p.edit { putString(newK, v); remove(oldK) }
            return v
        }
        return def
    }

    private fun setString(newK: String, v: String) {
        sp?.edit { putString(newK, v) }
    }

    // --- feature toggles (defaults ON) ---
    fun enablePk(): Boolean = getBoolMigrate(K_ENABLE_PK, L_ENABLE_PK, true)
    fun setEnablePk(v: Boolean) = setBool(K_ENABLE_PK, v)

    fun enableIsf(): Boolean = getBoolMigrate(K_ENABLE_ISF, L_ENABLE_ISF, true)
    fun setEnableIsf(v: Boolean) = setBool(K_ENABLE_ISF, v)

    fun allowDiaAbove9h(): Boolean = getBoolMigrate(K_ALLOW_DIA_ABOVE_9H, L_ALLOW_DIA_ABOVE_9H, true)
    fun setAllowDiaAbove9h(v: Boolean) = setBool(K_ALLOW_DIA_ABOVE_9H, v)

    // --- optional site context ---
    fun siteLocation(): String = getStringMigrate(K_SITE_LOCATION, L_SITE_LOCATION, "ABDOMEN")
    fun setSiteLocation(v: String) = setString(K_SITE_LOCATION, v)

    fun siteAgeEnabled(): Boolean = getBoolMigrate(K_SITE_AGE_ENABLED, L_SITE_AGE_ENABLED, false)
    fun setSiteAgeEnabled(v: Boolean) = setBool(K_SITE_AGE_ENABLED, v)

    fun siteAgeH(): String = getStringMigrate(K_SITE_AGE_H, L_SITE_AGE_H, "1")
    fun setSiteAgeH(v: String) = setString(K_SITE_AGE_H, v)

    // --- live state get/set (unchanged) ---
    data class SavedState(
        val diaH: Float,
        val tPeakMin: Int,
        val isfMult: Float,
        val savedAtMs: Long
    )

    fun loadState(): SavedState? {
        val p = sp ?: return null
        if (!p.contains(K_STATE_DIA_H) || !p.contains(K_STATE_TPEAK_MIN) || !p.contains(K_STATE_ISF_MULT))
            return null
        val dia = p.getFloat(K_STATE_DIA_H, 10.0f)
        val tp = p.getInt(K_STATE_TPEAK_MIN, 133)
        val isf = p.getFloat(K_STATE_ISF_MULT, 1.0f)
        val ts = p.getLong(K_STATE_TS_MS, 0L)
        return SavedState(dia, tp, isf, ts)
    }

    fun saveState(diaH: Float, tPeakMin: Int, isfMult: Float, nowMs: Long) {
        sp?.edit {
            putFloat(K_STATE_DIA_H, diaH)
            putInt(K_STATE_TPEAK_MIN, tPeakMin)
            putFloat(K_STATE_ISF_MULT, isfMult)
            putLong(K_STATE_TS_MS, nowMs)
        }
    }
}

package app.aaps.plugins.insulin.sipp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * Plugin-local prefs for SIPP. No core keys, no MainApp.
 * Call SippPrefs.init(context) once before reading.
 * Defaults are OFF unless set by user to avoid surprises.
 */
object SippPrefs {

    @Volatile private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    // Canonical feature keys
    private const val K_ENABLE_PK = "SIPP_enable_pk"
    private const val K_ENABLE_ISF = "SIPP_enable_isf"
    private const val K_ALLOW_DIA_ABOVE_9H = "SIPP_allow_dia_above_9h"

    // NEW: toggles for Instant Basal and Max Basal
    private const val K_ENABLE_BASAL = "SIPP_enable_basal"
    private const val K_ENABLE_MAX_BASAL = "SIPP_enable_max_basal"

    private const val K_SITE_LOCATION = "SIPP_site_location"   // "ABDOMEN" | "ARM" | "THIGH"
    private const val K_SITE_AGE_ENABLED = "SIPP_site_age_enabled"
    private const val K_SITE_AGE_H = "SIPP_site_age_h"         // string hours

    // Persisted live PK/PD state (for continuity across APK updates)
    private const val K_STATE_DIA_H = "SIPP_state_dia_h"
    private const val K_STATE_TPEAK_MIN = "SIPP_state_tpeak_min"
    private const val K_STATE_ISF_MULT = "SIPP_state_isf_mult"
    private const val K_STATE_TS_MS = "SIPP_state_timestamp_ms"

    // ISF readouts (USED = what dosing used; RAW = exp-TDD instant, unscaled)
    private const val K_LAST_ISF_USED_MGDL = "SIPP_last_isf_used_mgdl"
    private const val K_LAST_ISF_USED_TS = "SIPP_last_isf_used_ts"
    private const val K_LAST_ISF_RAW_MGDL = "SIPP_last_isf_raw_mgdl"
    private const val K_LAST_ISF_RAW_TS = "SIPP_last_isf_raw_ts"

    // Context for the last saved USED ISF (for UI hinting at lows)
    private const val K_LAST_ISF_BG_MGDL = "SIPP_last_isf_bg_mgdl"
    private const val K_LAST_ISF_TARGETLOW_MGDL = "SIPP_last_isf_targetlow_mgdl"

    // Instant basal & suggested max basal (U/h)
    private const val K_LAST_INST_BASAL_UPH = "SIPP_last_instant_basal_uph"
    private const val K_LAST_INST_BASAL_TS = "SIPP_last_instant_basal_ts"
    private const val K_LAST_MAX_BASAL_UPH = "SIPP_last_max_basal_uph"
    private const val K_LAST_MAX_BASAL_TS = "SIPP_last_max_basal_ts"

    // --- simple helpers ---
    private fun getBool(key: String): Boolean = sp?.getBoolean(key, false) ?: false
    private fun setBool(key: String, v: Boolean) {
        sp?.edit { putBoolean(key, v) }
    }

    private fun getString(key: String, def: String): String = sp?.getString(key, def) ?: def
    private fun setString(key: String, v: String) {
        sp?.edit { putString(key, v) }
    }

    // --- feature toggles (defaults OFF, user controls) ---
    fun enablePk(): Boolean = getBool(K_ENABLE_PK)
    fun setEnablePk(v: Boolean) = setBool(K_ENABLE_PK, v)

    fun enableIsf(): Boolean = getBool(K_ENABLE_ISF)
    fun setEnableIsf(v: Boolean) = setBool(K_ENABLE_ISF, v)

    fun allowDiaAbove9h(): Boolean = getBool(K_ALLOW_DIA_ABOVE_9H)
    fun setAllowDiaAbove9h(v: Boolean) = setBool(K_ALLOW_DIA_ABOVE_9H, v)

    // NEW: SIPP Instant Basal + Max Basal toggles
    fun enableBasal(): Boolean = getBool(K_ENABLE_BASAL)
    fun setEnableBasal(v: Boolean) = setBool(K_ENABLE_BASAL, v)

    fun enableMaxBasal(): Boolean = getBool(K_ENABLE_MAX_BASAL)
    fun setEnableMaxBasal(v: Boolean) = setBool(K_ENABLE_MAX_BASAL, v)

    // --- optional site context ---
    fun siteLocation(): String = getString(K_SITE_LOCATION, "ABDOMEN")
    fun setSiteLocation(v: String) = setString(K_SITE_LOCATION, v)

    fun siteAgeEnabled(): Boolean = getBool(K_SITE_AGE_ENABLED)
    fun setSiteAgeEnabled(v: Boolean) = setBool(K_SITE_AGE_ENABLED, v)

    fun siteAgeH(): String = getString(K_SITE_AGE_H, "1")
    fun setSiteAgeH(v: String) = setString(K_SITE_AGE_H, v)

    // --- live PK state get/set ---
    data class SavedState(
        val diaH: Float,
        val tPeakMin: Int,
        val isfMult: Float,
        val savedAtMs: Long
    )

    /** Returns last persisted PK state or null if never saved. */
    fun loadState(): SavedState? {
        val p = sp ?: return null
        if (!p.contains(K_STATE_DIA_H) || !p.contains(K_STATE_TPEAK_MIN) || !p.contains(K_STATE_ISF_MULT))
            return null
        val dia = p.getFloat(K_STATE_DIA_H, 9.0f)
        val tp = p.getInt(K_STATE_TPEAK_MIN, 120)
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

    // --- ISF persistence ---
    /** ISF the algorithm actually used for dosing (mg/dL per U), with UI context at save-time. */
    fun saveLastInstantIsfMgdl(isfMgdl: Double, tsMs: Long, bgMgdl: Double? = null, targetLowMgdl: Double? = null) {
        sp?.edit {
            putFloat(K_LAST_ISF_USED_MGDL, isfMgdl.toFloat())
            putLong(K_LAST_ISF_USED_TS, tsMs)
            if (bgMgdl != null) putFloat(K_LAST_ISF_BG_MGDL, bgMgdl.toFloat())
            if (targetLowMgdl != null) putFloat(K_LAST_ISF_TARGETLOW_MGDL, targetLowMgdl.toFloat())
        }
    }

    fun lastInstantIsfMgdl(): Double? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_ISF_USED_MGDL)) return null
        return p.getFloat(K_LAST_ISF_USED_MGDL, 0f).toDouble()
    }

    fun lastInstantIsfTsMs(): Long? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_ISF_USED_TS)) return null
        return p.getLong(K_LAST_ISF_USED_TS, 0L)
    }

    fun lastIsfContext(): Pair<Double?, Double?> {
        val p = sp ?: return Pair(null, null)
        val bg = if (p.contains(K_LAST_ISF_BG_MGDL)) p.getFloat(K_LAST_ISF_BG_MGDL, 0f).toDouble() else null
        val low = if (p.contains(K_LAST_ISF_TARGETLOW_MGDL)) p.getFloat(K_LAST_ISF_TARGETLOW_MGDL, 0f).toDouble() else null
        return Pair(bg, low)
    }

    /** Pure instant SIPP ISF (exp-weighted TDD, unscaled). */
    fun saveLastRawInstantIsfMgdl(isfMgdl: Double, tsMs: Long) {
        sp?.edit {
            putFloat(K_LAST_ISF_RAW_MGDL, isfMgdl.toFloat())
            putLong(K_LAST_ISF_RAW_TS, tsMs)
        }
    }

    fun lastRawInstantIsfMgdl(): Double? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_ISF_RAW_MGDL)) return null
        return p.getFloat(K_LAST_ISF_RAW_MGDL, 0f).toDouble()
    }

    fun lastRawInstantIsfTsMs(): Long? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_ISF_RAW_TS)) return null
        return p.getLong(K_LAST_ISF_RAW_TS, 0L)
    }

    // --- Instant basal & suggested max basal (U/h) ---
    fun saveLastInstantBasalUph(uph: Double, tsMs: Long) {
        sp?.edit {
            putFloat(K_LAST_INST_BASAL_UPH, uph.toFloat())
            putLong(K_LAST_INST_BASAL_TS, tsMs)
        }
    }

    fun lastInstantBasalUph(): Double? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_INST_BASAL_UPH)) return null
        return p.getFloat(K_LAST_INST_BASAL_UPH, 0f).toDouble()
    }

    fun lastInstantBasalTsMs(): Long? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_INST_BASAL_TS)) return null
        return p.getLong(K_LAST_INST_BASAL_TS, 0L)
    }

    fun saveLastMaxBasalUph(uph: Double, tsMs: Long) {
        sp?.edit {
            putFloat(K_LAST_MAX_BASAL_UPH, uph.toFloat())
            putLong(K_LAST_MAX_BASAL_TS, tsMs)
        }
    }

    fun lastMaxBasalUph(): Double? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_MAX_BASAL_UPH)) return null
        return p.getFloat(K_LAST_MAX_BASAL_UPH, 0f).toDouble()
    }

    fun lastMaxBasalTsMs(): Long? {
        val p = sp ?: return null
        if (!p.contains(K_LAST_MAX_BASAL_TS)) return null
        return p.getLong(K_LAST_MAX_BASAL_TS, 0L)
    }

    // --------- Export / Import (Settings JSON bridging) ----------

    fun packToJson(): JSONObject =
        JSONObject().apply {
            put("enablePk", enablePk())
            put("enableIsf", enableIsf())
            put("allowDiaAbove9h", allowDiaAbove9h())
            put("enableBasal", enableBasal())
            put("enableMaxBasal", enableMaxBasal())
            put("siteLocation", siteLocation())
            put("siteAgeEnabled", siteAgeEnabled())
            put("siteAgeH", siteAgeH())

            loadState()?.let {
                put("state_diaH", it.diaH.toDouble())
                put("state_tPeakMin", it.tPeakMin)
                put("state_isfMult", it.isfMult.toDouble())
                put("state_savedAtMs", it.savedAtMs)
            }

            lastInstantIsfMgdl()?.let { put("last_isf_used_mgdl", it) }
            lastInstantIsfTsMs()?.let { put("last_isf_used_ts", it) }
            lastRawInstantIsfMgdl()?.let { put("last_isf_raw_mgdl", it) }
            lastRawInstantIsfTsMs()?.let { put("last_isf_raw_ts", it) }

            val (bg, low) = lastIsfContext()
            bg?.let { put("last_isf_bg_mgdl", it) }
            low?.let { put("last_isf_targetlow_mgdl", it) }

            lastInstantBasalUph()?.let { put("last_instant_basal_uph", it) }
            lastInstantBasalTsMs()?.let { put("last_instant_basal_ts", it) }
            lastMaxBasalUph()?.let { put("last_max_basal_uph", it) }
            lastMaxBasalTsMs()?.let { put("last_max_basal_ts", it) }
        }

    fun applyFromJson(obj: JSONObject?) {
        if (obj == null) return
        setEnablePk(obj.optBoolean("enablePk", enablePk()))
        setEnableIsf(obj.optBoolean("enableIsf", enableIsf()))
        setAllowDiaAbove9h(obj.optBoolean("allowDiaAbove9h", allowDiaAbove9h()))
        setEnableBasal(obj.optBoolean("enableBasal", enableBasal()))
        setEnableMaxBasal(obj.optBoolean("enableMaxBasal", enableMaxBasal()))
        setSiteLocation(obj.optString("siteLocation", siteLocation()))
        setSiteAgeEnabled(obj.optBoolean("siteAgeEnabled", siteAgeEnabled()))
        setSiteAgeH(obj.optString("siteAgeH", siteAgeH()))

        val dia = obj.optDouble("state_diaH", Double.NaN)
        val tp = obj.optInt("state_tPeakMin", Int.MIN_VALUE)
        val im = obj.optDouble("state_isfMult", Double.NaN)
        val ts = obj.optLong("state_savedAtMs", 0L)
        if (!dia.isNaN() && tp != Int.MIN_VALUE && !im.isNaN() && ts != 0L) {
            saveState(dia.toFloat(), tp, im.toFloat(), ts)
        }

        val used = obj.optDouble("last_isf_used_mgdl", Double.NaN)
        val usedTs = obj.optLong("last_isf_used_ts", 0L)
        val bg = obj.optDouble("last_isf_bg_mgdl", Double.NaN)
        val low = obj.optDouble("last_isf_targetlow_mgdl", Double.NaN)
        if (!used.isNaN() && usedTs != 0L) {
            val bgOrNull = if (bg.isNaN()) null else bg
            val lowOrNull = if (low.isNaN()) null else low
            saveLastInstantIsfMgdl(used, usedTs, bgOrNull, lowOrNull)
        }

        val raw = obj.optDouble("last_isf_raw_mgdl", Double.NaN)
        val rawTs = obj.optLong("last_isf_raw_ts", 0L)
        if (!raw.isNaN() && rawTs != 0L) saveLastRawInstantIsfMgdl(raw, rawTs)

        val instBasal = obj.optDouble("last_instant_basal_uph", Double.NaN)
        val instTs = obj.optLong("last_instant_basal_ts", 0L)
        if (!instBasal.isNaN() && instTs != 0L) saveLastInstantBasalUph(instBasal, instTs)

        val maxBasal = obj.optDouble("last_max_basal_uph", Double.NaN)
        val maxTs = obj.optLong("last_max_basal_ts", 0L)
        if (!maxBasal.isNaN() && maxTs != 0L) saveLastMaxBasalUph(maxBasal, maxTs)
    }
}

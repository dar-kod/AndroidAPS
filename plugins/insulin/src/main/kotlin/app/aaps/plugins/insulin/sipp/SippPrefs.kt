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

    // ---------- Keys (feature flags) ----------
    private const val K_ENABLE_PK = "SIPP_enable_pk"
    private const val K_ENABLE_ISF = "SIPP_enable_isf"
    private const val K_ALLOW_DIA_ABOVE_9H = "SIPP_allow_dia_above_9h"

    // Instant Basal & Max Basal toggles
    private const val K_ENABLE_BASAL = "SIPP_enable_basal"
    private const val K_ENABLE_MAX_BASAL = "SIPP_enable_max_basal"

    // Site context
    private const val K_SITE_LOCATION = "SIPP_site_location"   // "ABDOMEN" | "ARM" | "THIGH"
    private const val K_SITE_AGE_ENABLED = "SIPP_site_age_enabled"
    private const val K_SITE_AGE_H = "SIPP_site_age_h"         // string hours

    // Insulin archetype for seeding ("AUTO" | "RAPID" | "FIASP" | "LYUMJEV")
    private const val K_INSULIN_ARCHETYPE = "SIPP_insulin_archetype"

    // Live PK/PD state
    private const val K_STATE_DIA_H = "SIPP_state_dia_h"
    private const val K_STATE_TPEAK_MIN = "SIPP_state_tpeak_min"
    private const val K_STATE_ISF_MULT = "SIPP_state_isf_mult"
    private const val K_STATE_TS_MS = "SIPP_state_timestamp_ms"

    // ISF readouts (USED/RAW) and context at save time
    private const val K_LAST_ISF_USED_MGDL = "SIPP_last_isf_used_mgdl"
    private const val K_LAST_ISF_USED_TS = "SIPP_last_isf_used_ts"
    private const val K_LAST_ISF_RAW_MGDL = "SIPP_last_isf_raw_mgdl"
    private const val K_LAST_ISF_RAW_TS = "SIPP_last_isf_raw_ts"
    private const val K_LAST_ISF_BG_MGDL = "SIPP_last_isf_bg_mgdl"
    private const val K_LAST_ISF_TARGETLOW_MGDL = "SIPP_last_isf_targetlow_mgdl"

    // Instant basal & suggested max basal (U/h)
    private const val K_LAST_INST_BASAL_UPH = "SIPP_last_instant_basal_uph"
    private const val K_LAST_INST_BASAL_TS = "SIPP_last_instant_basal_ts"
    private const val K_LAST_MAX_BASAL_UPH = "SIPP_last_max_basal_uph"
    private const val K_LAST_MAX_BASAL_TS = "SIPP_last_max_basal_ts"

    // ---------- NEW: Activity fusion toggles ----------
    private const val K_ENABLE_ACTIVITY = "SIPP_enable_activity_fusion" // master
    private const val K_USE_HR = "SIPP_use_hr"
    private const val K_USE_STEPS = "SIPP_use_steps"

    // ---------- NEW: Last-seen activity samples (for graceful fallback) ----------
    private const val K_LAST_HR_BPM = "SIPP_last_hr_bpm"
    private const val K_LAST_HR_TS = "SIPP_last_hr_ts"
    private const val K_LAST_SPM = "SIPP_last_steps_per_min"
    private const val K_LAST_SUSTAIN_MIN = "SIPP_last_activity_window_min"
    private const val K_LAST_STEPS_TS = "SIPP_last_steps_ts"

    // --- simple helpers ---
    private fun getBool(key: String, def: Boolean = false): Boolean = sp?.getBoolean(key, def) ?: def
    private fun setBool(key: String, v: Boolean) {
        sp?.edit { putBoolean(key, v) }
    }

    private fun getString(key: String, def: String): String = sp?.getString(key, def) ?: def
    private fun setString(key: String, v: String) {
        sp?.edit { putString(key, v) }
    }

    private fun getFloatOrNull(key: String): Float? {
        val p = sp ?: return null
        return if (p.contains(key)) p.getFloat(key, 0f) else null
    }

    private fun getLongOrNull(key: String): Long? {
        val p = sp ?: return null
        return if (p.contains(key)) p.getLong(key, 0L) else null
    }

    private fun getIntOrNull(key: String): Int? {
        val p = sp ?: return null
        return if (p.contains(key)) p.getInt(key, 0) else null
    }

    // --- feature toggles (defaults OFF) ---
    fun enablePk(): Boolean = getBool(K_ENABLE_PK)
    fun setEnablePk(v: Boolean) = setBool(K_ENABLE_PK, v)

    fun enableIsf(): Boolean = getBool(K_ENABLE_ISF)
    fun setEnableIsf(v: Boolean) = setBool(K_ENABLE_ISF, v)

    fun allowDiaAbove9h(): Boolean = getBool(K_ALLOW_DIA_ABOVE_9H)
    fun setAllowDiaAbove9h(v: Boolean) = setBool(K_ALLOW_DIA_ABOVE_9H, v)

    fun enableBasal(): Boolean = getBool(K_ENABLE_BASAL)
    fun setEnableBasal(v: Boolean) = setBool(K_ENABLE_BASAL, v)

    fun enableMaxBasal(): Boolean = getBool(K_ENABLE_MAX_BASAL)
    fun setEnableMaxBasal(v: Boolean) = setBool(K_ENABLE_MAX_BASAL, v)

    // --- activity fusion toggles (master + sub-switches) ---
    fun enableActivityFusion(): Boolean = getBool(K_ENABLE_ACTIVITY)
    fun setEnableActivityFusion(v: Boolean) = setBool(K_ENABLE_ACTIVITY, v)

    fun useHr(): Boolean = getBool(K_USE_HR)
    fun setUseHr(v: Boolean) = setBool(K_USE_HR, v)

    fun useSteps(): Boolean = getBool(K_USE_STEPS)
    fun setUseSteps(v: Boolean) = setBool(K_USE_STEPS, v)

    // --- optional site context ---
    fun siteLocation(): String = getString(K_SITE_LOCATION, "ABDOMEN")
    fun setSiteLocation(v: String) = setString(K_SITE_LOCATION, v)

    fun siteAgeEnabled(): Boolean = getBool(K_SITE_AGE_ENABLED)
    fun setSiteAgeEnabled(v: Boolean) = setBool(K_SITE_AGE_ENABLED, v)

    fun siteAgeH(): String = getString(K_SITE_AGE_H, "1")
    fun setSiteAgeH(v: String) = setString(K_SITE_AGE_H, v)

    // --- insulin archetype for seeding (AUTO default) ---
    fun insulinArchetype(): String = getString(K_INSULIN_ARCHETYPE, "AUTO")
    fun setInsulinArchetype(v: String) = setString(K_INSULIN_ARCHETYPE, v)

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
        if (!p.contains(K_STATE_DIA_H) || !p.contains(K_STATE_TPEAK_MIN) || !p.contains(K_STATE_ISF_MULT)) return null
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
    /** ISF the algorithm actually used for dosing (mg/dL per U), with optional UI context at save-time. */
    fun saveLastInstantIsfMgdl(
        isfMgdl: Double,
        tsMs: Long,
        bgMgdl: Double? = null,
        targetLowMgdl: Double? = null
    ) {
        sp?.edit {
            putFloat(K_LAST_ISF_USED_MGDL, isfMgdl.toFloat())
            putLong(K_LAST_ISF_USED_TS, tsMs)
            if (bgMgdl != null) putFloat(K_LAST_ISF_BG_MGDL, bgMgdl.toFloat())
            if (targetLowMgdl != null) putFloat(K_LAST_ISF_TARGETLOW_MGDL, targetLowMgdl.toFloat())
        }
    }

    fun lastInstantIsfMgdl(): Double? = getFloatOrNull(K_LAST_ISF_USED_MGDL)?.toDouble()
    fun lastInstantIsfTsMs(): Long? = getLongOrNull(K_LAST_ISF_USED_TS)

    fun lastIsfContext(): Pair<Double?, Double?> {
        val bg = getFloatOrNull(K_LAST_ISF_BG_MGDL)?.toDouble()
        val low = getFloatOrNull(K_LAST_ISF_TARGETLOW_MGDL)?.toDouble()
        return Pair(bg, low)
    }

    /** Pure instant SIPP ISF (exp-weighted TDD, unscaled). */
    fun saveLastRawInstantIsfMgdl(isfMgdl: Double, tsMs: Long) {
        sp?.edit {
            putFloat(K_LAST_ISF_RAW_MGDL, isfMgdl.toFloat())
            putLong(K_LAST_ISF_RAW_TS, tsMs)
        }
    }

    fun lastRawInstantIsfMgdl(): Double? = getFloatOrNull(K_LAST_ISF_RAW_MGDL)?.toDouble()
    fun lastRawInstantIsfTsMs(): Long? = getLongOrNull(K_LAST_ISF_RAW_TS)

    // --- Instant basal & suggested max basal (U/h) ---
    fun saveLastInstantBasalUph(uph: Double, tsMs: Long) {
        sp?.edit {
            putFloat(K_LAST_INST_BASAL_UPH, uph.toFloat())
            putLong(K_LAST_INST_BASAL_TS, tsMs)
        }
    }
    fun lastInstantBasalUph(): Double? = getFloatOrNull(K_LAST_INST_BASAL_UPH)?.toDouble()
    fun lastInstantBasalTsMs(): Long? = getLongOrNull(K_LAST_INST_BASAL_TS)

    fun saveLastMaxBasalUph(uph: Double, tsMs: Long) {
        sp?.edit {
            putFloat(K_LAST_MAX_BASAL_UPH, uph.toFloat())
            putLong(K_LAST_MAX_BASAL_TS, tsMs)
        }
    }
    fun lastMaxBasalUph(): Double? = getFloatOrNull(K_LAST_MAX_BASAL_UPH)?.toDouble()
    fun lastMaxBasalTsMs(): Long? = getLongOrNull(K_LAST_MAX_BASAL_TS)

    // --- NEW: Persisted activity samples (graceful fallback for SIPP controller) ---
    fun saveLastHrBpm(bpm: Int?, tsMs: Long) {
        sp?.edit {
            if (bpm == null) {
                remove(K_LAST_HR_BPM)
                // still store TS so we know when it went null
                putLong(K_LAST_HR_TS, tsMs)
            } else {
                putInt(K_LAST_HR_BPM, bpm)
                putLong(K_LAST_HR_TS, tsMs)
            }
        }
    }

    fun lastHrBpm(): Int? = getIntOrNull(K_LAST_HR_BPM)
    fun lastHrTsMs(): Long? = getLongOrNull(K_LAST_HR_TS)

    /**
     * Save latest cadence snapshot:
     *  - stepsPerMin: SPM over the last complete minute (nullable if unknown)
     *  - sustainedActiveMin: consecutive active minutes window (0 if not active)
     */
    fun saveLastCadence(spm: Int?, sustainedActiveMin: Int, tsMs: Long) {
        sp?.edit {
            if (spm == null) remove(K_LAST_SPM) else putInt(K_LAST_SPM, spm)
            putInt(K_LAST_SUSTAIN_MIN, sustainedActiveMin.coerceAtLeast(0))
            putLong(K_LAST_STEPS_TS, tsMs)
        }
    }

    fun lastStepsPerMin(): Int? = getIntOrNull(K_LAST_SPM)
    fun lastSustainedActiveMin(): Int = getIntOrNull(K_LAST_SUSTAIN_MIN) ?: 0
    fun lastStepsTsMs(): Long? = getLongOrNull(K_LAST_STEPS_TS)

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
            put("insulinArchetype", insulinArchetype())

            // Activity fusion flags
            put("enableActivityFusion", enableActivityFusion())
            put("useHr", useHr())
            put("useSteps", useSteps())

            // Live state
            loadState()?.let {
                put("state_diaH", it.diaH.toDouble())
                put("state_tPeakMin", it.tPeakMin)
                put("state_isfMult", it.isfMult.toDouble())
                put("state_savedAtMs", it.savedAtMs)
            }

            // ISF readouts
            lastInstantIsfMgdl()?.let { put("last_isf_used_mgdl", it) }
            lastInstantIsfTsMs()?.let { put("last_isf_used_ts", it) }
            lastRawInstantIsfMgdl()?.let { put("last_isf_raw_mgdl", it) }
            lastRawInstantIsfTsMs()?.let { put("last_isf_raw_ts", it) }
            val (bg, low) = lastIsfContext()
            bg?.let { put("last_isf_bg_mgdl", it) }
            low?.let { put("last_isf_targetlow_mgdl", it) }

            // Basal suggestions
            lastInstantBasalUph()?.let { put("last_instant_basal_uph", it) }
            lastInstantBasalTsMs()?.let { put("last_instant_basal_ts", it) }
            lastMaxBasalUph()?.let { put("last_max_basal_uph", it) }
            lastMaxBasalTsMs()?.let { put("last_max_basal_ts", it) }

            // Activity snapshots
            lastHrBpm()?.let { put("last_hr_bpm", it) }
            lastHrTsMs()?.let { put("last_hr_ts", it) }
            lastStepsPerMin()?.let { put("last_spm", it) }
            put("last_sustain_min", lastSustainedActiveMin())
            lastStepsTsMs()?.let { put("last_steps_ts", it) }
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
        setInsulinArchetype(obj.optString("insulinArchetype", insulinArchetype()))

        // Activity fusion flags
        setEnableActivityFusion(obj.optBoolean("enableActivityFusion", enableActivityFusion()))
        setUseHr(obj.optBoolean("useHr", useHr()))
        setUseSteps(obj.optBoolean("useSteps", useSteps()))

        // Live state
        val dia = obj.optDouble("state_diaH", Double.NaN)
        val tp = obj.optInt("state_tPeakMin", Int.MIN_VALUE)
        val im = obj.optDouble("state_isfMult", Double.NaN)
        val ts = obj.optLong("state_savedAtMs", 0L)
        if (!dia.isNaN() && tp != Int.MIN_VALUE && !im.isNaN() && ts != 0L) {
            saveState(dia.toFloat(), tp, im.toFloat(), ts)
        }

        // ISF readouts
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

        // Basal suggestions
        val instBasal = obj.optDouble("last_instant_basal_uph", Double.NaN)
        val instTs = obj.optLong("last_instant_basal_ts", 0L)
        if (!instBasal.isNaN() && instTs != 0L) saveLastInstantBasalUph(instBasal, instTs)
        val maxBasal = obj.optDouble("last_max_basal_uph", Double.NaN)
        val maxTs = obj.optLong("last_max_basal_ts", 0L)
        if (!maxBasal.isNaN() && maxTs != 0L) saveLastMaxBasalUph(maxBasal, maxTs)

        // Activity snapshots
        val hrBpm = obj.optInt("last_hr_bpm", Int.MIN_VALUE)
        val hrTs = obj.optLong("last_hr_ts", 0L)
        if (hrTs != 0L) saveLastHrBpm(if (hrBpm == Int.MIN_VALUE) null else hrBpm, hrTs)

        val spm = obj.optInt("last_spm", Int.MIN_VALUE)
        val sustain = obj.optInt("last_sustain_min", 0)
        val stepsTs = obj.optLong("last_steps_ts", 0L)
        if (stepsTs != 0L) saveLastCadence(if (spm == Int.MIN_VALUE) null else spm, sustain, stepsTs)
    }
}

package app.aaps.plugins.insulin.sipp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * SIPP local preferences & lightweight state.
 * Initialize once via SippPrefs.init(context) before use.
 */
object SippPrefs {

    @Volatile private var sp: SharedPreferences? = null
    fun init(context: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            }
        }
    }

    // ===== Feature flags =====
    private const val K_ENABLE_PK = "SIPP_enable_pk"                 // DIA/Peak autotune
    private const val K_ENABLE_ISF = "SIPP_enable_isf"               // Instant ISF
    private const val K_ALLOW_DIA_ABOVE_9H = "SIPP_allow_dia_above_9h"

    // Basal derivations
    private const val K_ENABLE_BASAL = "SIPP_enable_basal"           // Apply Instant Basal
    private const val K_ENABLE_MAX_BASAL = "SIPP_enable_max_basal"   // Apply Max Temp Basal cap

    // Site context
    private const val K_SITE_LOCATION = "SIPP_site_location"         // "ABDOMEN"|"ARM"|"THIGH"
    private const val K_SITE_AGE_ENABLED = "SIPP_site_age_enabled"
    private const val K_SITE_AGE_H = "SIPP_site_age_h"               // string hours

    // Insulin archetype seeding
    private const val K_INSULIN_ARCHETYPE = "SIPP_insulin_archetype" // "AUTO"|"RAPID"|"FIASP"|"LYUMJEV"

    // Activity fusion
    private const val K_ENABLE_ACTIVITY = "SIPP_enable_activity_fusion"
    private const val K_USE_HR = "SIPP_use_hr"
    private const val K_USE_STEPS = "SIPP_use_steps"

    // Sleep & recovery
    private const val K_ENABLE_SLEEP_RECOVERY = "SIPP_enable_sleep_recovery"
    private const val K_MANUAL_SLEEP_ENABLED = "SIPP_manual_sleep_enabled"
    private const val K_MANUAL_SLEEP_START_MIN = "SIPP_manual_sleep_start_min" // 0..1439
    private const val K_MANUAL_SLEEP_END_MIN = "SIPP_manual_sleep_end_min"     // 0..1439

    // Live PK/PD state snapshot
    private const val K_STATE_DIA_H = "SIPP_state_dia_h"
    private const val K_STATE_TPEAK_MIN = "SIPP_state_tpeak_min"
    private const val K_STATE_ISF_MULT = "SIPP_state_isf_mult"
    private const val K_STATE_TS_MS = "SIPP_state_timestamp_ms"

    // ISF readouts (USED by loop) + context
    private const val K_LAST_ISF_USED_MGDL = "SIPP_last_isf_used_mgdl"
    private const val K_LAST_ISF_USED_TS = "SIPP_last_isf_used_ts"
    private const val K_LAST_ISF_BG_MGDL = "SIPP_last_isf_bg_mgdl"
    private const val K_LAST_ISF_TARGETLOW_MGDL = "SIPP_last_isf_targetlow_mgdl"

    // ISF (raw alternative for diagnostics)
    private const val K_LAST_ISF_RAW_MGDL = "SIPP_last_isf_raw_mgdl"
    private const val K_LAST_ISF_RAW_TS = "SIPP_last_isf_raw_ts"

    // Instant Basal & Max Basal suggestions (U/h)
    private const val K_LAST_INST_BASAL_UPH = "SIPP_last_instant_basal_uph"
    private const val K_LAST_INST_BASAL_TS = "SIPP_last_instant_basal_ts"
    private const val K_LAST_MAX_BASAL_UPH = "SIPP_last_max_basal_uph"
    private const val K_LAST_MAX_BASAL_TS = "SIPP_last_max_basal_ts"

    // Last activity samples (optional; used in UI/state replay)
    private const val K_LAST_HR_BPM = "SIPP_last_hr_bpm"
    private const val K_LAST_HR_TS = "SIPP_last_hr_ts"
    private const val K_LAST_SPM = "SIPP_last_steps_per_min"
    private const val K_LAST_SUSTAIN_MIN = "SIPP_last_activity_window_min"
    private const val K_LAST_STEPS_TS = "SIPP_last_steps_ts"

    // ===== Primitive helpers =====
    private fun p(): SharedPreferences = requireNotNull(sp) { "SippPrefs.init(context) not called" }
    private fun getBool(k: String, def: Boolean = false) = p().getBoolean(k, def)
    private fun setBool(k: String, v: Boolean) = p().edit { putBoolean(k, v) }
    private fun getString(k: String, def: String) = p().getString(k, def) ?: def
    private fun setString(k: String, v: String) = p().edit { putString(k, v) }
    private fun getFloatOrNull(k: String): Float? = if (p().contains(k)) p().getFloat(k, 0f) else null
    private fun getLongOrNull(k: String): Long? = if (p().contains(k)) p().getLong(k, 0L) else null
    private fun getIntOrNull(k: String): Int? = if (p().contains(k)) p().getInt(k, 0) else null

    // ===== Feature toggles =====
    fun enablePk() = getBool(K_ENABLE_PK)
    fun setEnablePk(v: Boolean) = setBool(K_ENABLE_PK, v)

    fun enableIsf() = getBool(K_ENABLE_ISF)
    fun setEnableIsf(v: Boolean) = setBool(K_ENABLE_ISF, v)

    fun allowDiaAbove9h() = getBool(K_ALLOW_DIA_ABOVE_9H)
    fun setAllowDiaAbove9h(v: Boolean) = setBool(K_ALLOW_DIA_ABOVE_9H, v)

    fun enableBasal() = getBool(K_ENABLE_BASAL)
    fun setEnableBasal(v: Boolean) = setBool(K_ENABLE_BASAL, v)

    fun enableMaxBasal() = getBool(K_ENABLE_MAX_BASAL)
    fun setEnableMaxBasal(v: Boolean) = setBool(K_ENABLE_MAX_BASAL, v)

    // Activity fusion
    fun enableActivityFusion() = getBool(K_ENABLE_ACTIVITY)
    fun setEnableActivityFusion(v: Boolean) = setBool(K_ENABLE_ACTIVITY, v)

    fun useHr() = getBool(K_USE_HR)
    fun setUseHr(v: Boolean) = setBool(K_USE_HR, v)

    fun useSteps() = getBool(K_USE_STEPS)
    fun setUseSteps(v: Boolean) = setBool(K_USE_STEPS, v)

    // Sleep & recovery (auto/manual window)
    fun enableSleepRecovery() = getBool(K_ENABLE_SLEEP_RECOVERY)
    fun setEnableSleepRecovery(v: Boolean) = setBool(K_ENABLE_SLEEP_RECOVERY, v)

    fun manualSleepEnabled() = getBool(K_MANUAL_SLEEP_ENABLED)
    fun setManualSleepEnabled(v: Boolean) = setBool(K_MANUAL_SLEEP_ENABLED, v)

    fun manualSleepStartMin(): Int = getIntOrNull(K_MANUAL_SLEEP_START_MIN) ?: (23 * 60) // default 23:00
    fun setManualSleepStartMin(minSinceMidnight: Int) =
        p().edit { putInt(K_MANUAL_SLEEP_START_MIN, minSinceMidnight.coerceIn(0, 1439)) }

    fun manualSleepEndMin(): Int = getIntOrNull(K_MANUAL_SLEEP_END_MIN) ?: (7 * 60) // default 07:00
    fun setManualSleepEndMin(minSinceMidnight: Int) =
        p().edit { putInt(K_MANUAL_SLEEP_END_MIN, minSinceMidnight.coerceIn(0, 1439)) }

    // Site context
    fun siteLocation(): String = getString(K_SITE_LOCATION, "ABDOMEN")
    fun setSiteLocation(v: String) = setString(K_SITE_LOCATION, v)

    fun siteAgeEnabled() = getBool(K_SITE_AGE_ENABLED)
    fun setSiteAgeEnabled(v: Boolean) = setBool(K_SITE_AGE_ENABLED, v)

    fun siteAgeH(): String = getString(K_SITE_AGE_H, "1")
    fun setSiteAgeH(v: String) = setString(K_SITE_AGE_H, v)

    // Insulin archetype
    fun insulinArchetype(): String = getString(K_INSULIN_ARCHETYPE, "AUTO")
    fun setInsulinArchetype(v: String) = setString(K_INSULIN_ARCHETYPE, v)

    // ===== Live PK/PD state =====
    data class SavedState(val diaH: Float, val tPeakMin: Int, val isfMult: Float, val savedAtMs: Long)

    fun loadState(): SavedState? {
        if (!p().contains(K_STATE_DIA_H) || !p().contains(K_STATE_TPEAK_MIN) || !p().contains(K_STATE_ISF_MULT)) return null
        val dia = p().getFloat(K_STATE_DIA_H, 9.0f)
        val tp = p().getInt(K_STATE_TPEAK_MIN, 120)
        val im = p().getFloat(K_STATE_ISF_MULT, 1.0f)
        val ts = p().getLong(K_STATE_TS_MS, 0L)
        return SavedState(dia, tp, im, ts)
    }

    fun saveState(diaH: Float, tPeakMin: Int, isfMult: Float, nowMs: Long) {
        p().edit {
            putFloat(K_STATE_DIA_H, diaH)
            putInt(K_STATE_TPEAK_MIN, tPeakMin)
            putFloat(K_STATE_ISF_MULT, isfMult)
            putLong(K_STATE_TS_MS, nowMs)
        }
    }

    // ===== ISF persistence (USED) + context =====
    fun saveLastInstantIsfMgdl(isfMgdl: Double, tsMs: Long, bgMgdl: Double? = null, targetLowMgdl: Double? = null) {
        p().edit {
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

    // ISF raw (diagnostic)
    fun saveLastRawInstantIsfMgdl(isfMgdl: Double, tsMs: Long) {
        p().edit {
            putFloat(K_LAST_ISF_RAW_MGDL, isfMgdl.toFloat())
            putLong(K_LAST_ISF_RAW_TS, tsMs)
        }
    }

    fun lastRawInstantIsfMgdl(): Double? = getFloatOrNull(K_LAST_ISF_RAW_MGDL)?.toDouble()
    fun lastRawInstantIsfTsMs(): Long? = getLongOrNull(K_LAST_ISF_RAW_TS)

    // ===== Basal suggestions =====
    fun saveLastInstantBasalUph(uph: Double, tsMs: Long) {
        p().edit {
            putFloat(K_LAST_INST_BASAL_UPH, uph.toFloat())
            putLong(K_LAST_INST_BASAL_TS, tsMs)
        }
    }
    fun lastInstantBasalUph(): Double? = getFloatOrNull(K_LAST_INST_BASAL_UPH)?.toDouble()
    fun lastInstantBasalTsMs(): Long? = getLongOrNull(K_LAST_INST_BASAL_TS)

    fun saveLastMaxBasalUph(uph: Double, tsMs: Long) {
        p().edit {
            putFloat(K_LAST_MAX_BASAL_UPH, uph.toFloat())
            putLong(K_LAST_MAX_BASAL_TS, tsMs)
        }
    }
    fun lastMaxBasalUph(): Double? = getFloatOrNull(K_LAST_MAX_BASAL_UPH)?.toDouble()
    fun lastMaxBasalTsMs(): Long? = getLongOrNull(K_LAST_MAX_BASAL_TS)

    // ===== Activity snapshots (optional UI/state replay) =====
    fun saveLastHrBpm(bpm: Int?, tsMs: Long) {
        p().edit {
            if (bpm == null) remove(K_LAST_HR_BPM) else putInt(K_LAST_HR_BPM, bpm)
            putLong(K_LAST_HR_TS, tsMs)
        }
    }
    fun lastHrBpm(): Int? = getIntOrNull(K_LAST_HR_BPM)
    fun lastHrTsMs(): Long? = getLongOrNull(K_LAST_HR_TS)

    fun saveLastCadence(spm: Int?, sustainedActiveMin: Int, tsMs: Long) {
        p().edit {
            if (spm == null) remove(K_LAST_SPM) else putInt(K_LAST_SPM, spm)
            putInt(K_LAST_SUSTAIN_MIN, sustainedActiveMin.coerceAtLeast(0))
            putLong(K_LAST_STEPS_TS, tsMs)
        }
    }
    fun lastStepsPerMin(): Int? = getIntOrNull(K_LAST_SPM)
    fun lastSustainedActiveMin(): Int = getIntOrNull(K_LAST_SUSTAIN_MIN) ?: 0
    fun lastStepsTsMs(): Long? = getLongOrNull(K_LAST_STEPS_TS)

    // ===== Export / Import =====
    fun packToJson(): JSONObject = JSONObject().apply {
        // toggles
        put("enablePk", enablePk())
        put("enableIsf", enableIsf())
        put("allowDiaAbove9h", allowDiaAbove9h())
        put("enableBasal", enableBasal())
        put("enableMaxBasal", enableMaxBasal())

        // site + archetype
        put("siteLocation", siteLocation())
        put("siteAgeEnabled", siteAgeEnabled())
        put("siteAgeH", siteAgeH())
        put("insulinArchetype", insulinArchetype())

        // activity + sleep
        put("enableActivityFusion", enableActivityFusion())
        put("useHr", useHr())
        put("useSteps", useSteps())
        put("enableSleepRecovery", enableSleepRecovery())
        put("manualSleepEnabled", manualSleepEnabled())
        put("manualSleepStartMin", manualSleepStartMin())
        put("manualSleepEndMin", manualSleepEndMin())

        // live state
        loadState()?.let {
            put("state_diaH", it.diaH.toDouble())
            put("state_tPeakMin", it.tPeakMin)
            put("state_isfMult", it.isfMult.toDouble())
            put("state_savedAtMs", it.savedAtMs)
        }

        // ISF (used/raw) + context
        lastInstantIsfMgdl()?.let { put("last_isf_used_mgdl", it) }
        lastInstantIsfTsMs()?.let { put("last_isf_used_ts", it) }
        lastIsfContext().first?.let { put("last_isf_bg_mgdl", it) }
        lastIsfContext().second?.let { put("last_isf_targetlow_mgdl", it) }
        lastRawInstantIsfMgdl()?.let { put("last_isf_raw_mgdl", it) }
        lastRawInstantIsfTsMs()?.let { put("last_isf_raw_ts", it) }

        // basal suggestions
        lastInstantBasalUph()?.let { put("last_instant_basal_uph", it) }
        lastInstantBasalTsMs()?.let { put("last_instant_basal_ts", it) }
        lastMaxBasalUph()?.let { put("last_max_basal_uph", it) }
        lastMaxBasalTsMs()?.let { put("last_max_basal_ts", it) }

        // activity snapshots
        lastHrBpm()?.let { put("last_hr_bpm", it) }
        lastHrTsMs()?.let { put("last_hr_ts", it) }
        lastStepsPerMin()?.let { put("last_spm", it) }
        put("last_sustain_min", lastSustainedActiveMin())
        lastStepsTsMs()?.let { put("last_steps_ts", it) }
    }

    fun applyFromJson(obj: JSONObject?) {
        if (obj == null) return

        // toggles
        setEnablePk(obj.optBoolean("enablePk", enablePk()))
        setEnableIsf(obj.optBoolean("enableIsf", enableIsf()))
        setAllowDiaAbove9h(obj.optBoolean("allowDiaAbove9h", allowDiaAbove9h()))
        setEnableBasal(obj.optBoolean("enableBasal", enableBasal()))
        setEnableMaxBasal(obj.optBoolean("enableMaxBasal", enableMaxBasal()))

        // site + archetype
        setSiteLocation(obj.optString("siteLocation", siteLocation()))
        setSiteAgeEnabled(obj.optBoolean("siteAgeEnabled", siteAgeEnabled()))
        setSiteAgeH(obj.optString("siteAgeH", siteAgeH()))
        setInsulinArchetype(obj.optString("insulinArchetype", insulinArchetype()))

        // activity + sleep
        setEnableActivityFusion(obj.optBoolean("enableActivityFusion", enableActivityFusion()))
        setUseHr(obj.optBoolean("useHr", useHr()))
        setUseSteps(obj.optBoolean("useSteps", useSteps()))
        setEnableSleepRecovery(obj.optBoolean("enableSleepRecovery", enableSleepRecovery()))
        setManualSleepEnabled(obj.optBoolean("manualSleepEnabled", manualSleepEnabled()))
        setManualSleepStartMin(obj.optInt("manualSleepStartMin", manualSleepStartMin()))
        setManualSleepEndMin(obj.optInt("manualSleepEndMin", manualSleepEndMin()))

        // live state
        val dia = obj.optDouble("state_diaH", Double.NaN)
        val tp = obj.optInt("state_tPeakMin", Int.MIN_VALUE)
        val im = obj.optDouble("state_isfMult", Double.NaN)
        val ts = obj.optLong("state_savedAtMs", 0L)
        if (!dia.isNaN() && tp != Int.MIN_VALUE && !im.isNaN() && ts != 0L) {
            saveState(dia.toFloat(), tp, im.toFloat(), ts)
        }

        // ISF (used/raw) + context
        val used = obj.optDouble("last_isf_used_mgdl", Double.NaN)
        val usedTs = obj.optLong("last_isf_used_ts", 0L)
        val bg = obj.optDouble("last_isf_bg_mgdl", Double.NaN)
        val low = obj.optDouble("last_isf_targetlow_mgdl", Double.NaN)
        if (!used.isNaN() && usedTs != 0L) {
            saveLastInstantIsfMgdl(used, usedTs, if (bg.isNaN()) null else bg, if (low.isNaN()) null else low)
        }
        val raw = obj.optDouble("last_isf_raw_mgdl", Double.NaN)
        val rawTs = obj.optLong("last_isf_raw_ts", 0L)
        if (!raw.isNaN() && rawTs != 0L) saveLastRawInstantIsfMgdl(raw, rawTs)

        // basal suggestions
        val instBasal = obj.optDouble("last_instant_basal_uph", Double.NaN)
        val instTs = obj.optLong("last_instant_basal_ts", 0L)
        if (!instBasal.isNaN() && instTs != 0L) saveLastInstantBasalUph(instBasal, instTs)
        val maxBasal = obj.optDouble("last_max_basal_uph", Double.NaN)
        val maxTs = obj.optLong("last_max_basal_ts", 0L)
        if (!maxBasal.isNaN() && maxTs != 0L) saveLastMaxBasalUph(maxBasal, maxTs)

        // activity snapshots
        val hrBpm = obj.optInt("last_hr_bpm", Int.MIN_VALUE)
        val hrTs = obj.optLong("last_hr_ts", 0L)
        if (hrTs != 0L) saveLastHrBpm(if (hrBpm == Int.MIN_VALUE) null else hrBpm, hrTs)
        val spm = obj.optInt("last_spm", Int.MIN_VALUE)
        val sustain = obj.optInt("last_sustain_min", lastSustainedActiveMin())
        val stepsTs = obj.optLong("last_steps_ts", 0L)
        if (stepsTs != 0L) saveLastCadence(if (spm == Int.MIN_VALUE) null else spm, sustain, stepsTs)
    }
}

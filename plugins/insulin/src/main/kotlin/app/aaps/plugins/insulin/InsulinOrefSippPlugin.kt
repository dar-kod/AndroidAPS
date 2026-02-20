package app.aaps.plugins.insulin

import android.content.Context
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.plugins.insulin.sipp.SentinelPkPdController
import app.aaps.plugins.insulin.sipp.SippPrefs
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Oref Free-Peak insulin with SIPP PK handoff (DIA/Peak),
 * live ISF readout, and live-derived Basal / Max Basal readouts.
 * Adds “SIPP – Sleep” fragment (Auto vs Manual, mutually exclusive, HH:MM inputs).
 */
@Singleton
class InsulinOrefSippPlugin @Inject constructor(
    private val preferences: Preferences,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction,
    private val sipp: SentinelPkPdController,
    sharedPreferences: android.content.SharedPreferences
) : InsulinOrefBasePlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

    init {
        SippPrefs.init(sharedPreferences)
    }

    private companion object {
        private const val GLOBAL_PEAK_MIN_MIN = 45
        private const val GLOBAL_PEAK_MAX_DEFAULT = 210
        private const val GLOBAL_PEAK_MAX_EXTENDED = 240
        private const val RAMP_START_DIA_H = 6f
        private const val RAMP_END_DIA_H = 12f
        private const val CAP_AT_6H_MIN = 120
    }

    private var sippDiaRowRef: Preference? = null
    private var sippPeakRowRef: Preference? = null
    private var sippIsfRowRef: Preference? = null
    private var sippBasalRowRef: Preference? = null
    private var sippMaxBasalRowRef: Preference? = null
    private var sippDiagRowRef: Preference? = null

    private var activityReadoutRef: Preference? = null
    private var hrPrefRef: SwitchPreferenceCompat? = null
    private var stepsPrefRef: SwitchPreferenceCompat? = null

    private val busSubs = CompositeDisposable()

    override fun onStart() {
        super.onStart()
        runCatching {
            busSubs.add(rxBus.toObservable(EventNewBG::class.java).subscribe({ safeUiRefresh() }, { }))
            busSubs.add(rxBus.toObservable(EventAPSCalculationFinished::class.java).subscribe({ safeUiRefresh() }, { }))
        }
    }

    override fun onStop() {
        super.onStop()
        busSubs.clear()
    }

    override val id: Insulin.InsulinType get() = Insulin.InsulinType.OREF_SIPP
    override val friendlyName get(): String = rh.gs(R.string.sipp_instant_insulin)

    init {
        pluginDescription
            .pluginIcon(R.drawable.ic_insulin)
            .pluginName(R.string.sipp_instant_insulin)
            .preferencesId(PluginDescription.PREFERENCE_SCREEN)
            .description(R.string.description_insulin_sipp_instant)
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(IntKey.InsulinOrefPeak, preferences)
            .put("SIPP_CONFIG", SippPrefs.packToJson())

    override fun applyConfiguration(configuration: JSONObject) {
        configuration.store(IntKey.InsulinOrefPeak, preferences)
        SippPrefs.applyFromJson(configuration.optJSONObject("SIPP_CONFIG"))
    }

    override fun commentStandardText(): String = rh.gs(R.string.insulin_peak_time) + ": " + peak

    /** DIA from SIPP when PK enabled; otherwise Profile DIA. Sleep-aware ceiling applies if enabled. */
    override val userDefinedDia: Double
        get() {
            val profileDia = profileFunction.getProfile()?.dia ?: hardLimits.minDia()
            if (!SippPrefs.enablePk()) return profileDia

            val persisted = SippPrefs.loadState()
            val baseDia = (persisted?.diaH ?: sipp.current().diaH).toDouble()

            val allowExtended = SippPrefs.allowDiaAbove9h()
            val userCeil = if (allowExtended) 24.0 else 9.0

            val sleepMasterOn = SippPrefs.enableSleepRecovery()
            val manualOn = SippPrefs.manualSleepEnabled()
            val inManualSleep = manualOn && isNowInWindow(SippPrefs.manualSleepStartMin(), SippPrefs.manualSleepEndMin())

            val autoOn = SippPrefs.sleepAutoEnabled()
            val autoSleep = if (autoOn) runCatching {
                val snap = sipp.activitySnapshot()
                // ActivitySnapshot.sustainedActiveMin is ACTIVE minutes within a 10‑min window (0..10).
                // Auto-sleep should trigger on sustained INACTIVITY, not >=20.
                snap.fusionEnabled && (snap.hrEnabled || snap.cadenceEnabled) &&
                    !snap.hintActive && !snap.hrActive && !snap.cadenceActive && snap.sustainedActiveMin <= 1
            }.getOrDefault(false) else false

            val sleepCeilingActive = sleepMasterOn && (inManualSleep || autoSleep)
            val sleepCeil = if (sleepCeilingActive) minOf(userCeil, 24.0) else userCeil

            return baseDia.coerceIn(4.5, sleepCeil)
        }

    /** Peak (minutes) from SIPP when PK enabled; otherwise preference. */
    override val peak: Int
        get() = if (SippPrefs.enablePk()) {
            val profMin = preferences.get(IntKey.InsulinOrefPeak)
            val persisted = SippPrefs.loadState()
            val curDiaH = ((persisted?.diaH ?: sipp.current().diaH))
                .coerceIn(4.5f, if (SippPrefs.allowDiaAbove9h()) 24f else 12f)
            val fromSippMin = (persisted?.tPeakMin
                ?: (sipp.current().peakH?.times(60f)?.roundToInt() ?: profMin))
            val logicalMax = peakLogicalMax(curDiaH, SippPrefs.allowDiaAbove9h())
            fromSippMin.coerceIn(GLOBAL_PEAK_MIN_MIN, logicalMax)
        } else {
            preferences.get(IntKey.InsulinOrefPeak)
        }

    private fun peakLogicalMax(diaH: Float, allowExtended: Boolean): Int {
        val globalMax = if (allowExtended) GLOBAL_PEAK_MAX_EXTENDED else GLOBAL_PEAK_MAX_DEFAULT
        if (diaH <= RAMP_START_DIA_H) return CAP_AT_6H_MIN.coerceAtMost(globalMax)
        if (diaH < RAMP_END_DIA_H) {
            val t = (diaH - RAMP_START_DIA_H) / (RAMP_END_DIA_H - RAMP_START_DIA_H)
            val cap = (CAP_AT_6H_MIN + t * (globalMax - CAP_AT_6H_MIN)).roundToInt()
            return cap.coerceIn(GLOBAL_PEAK_MIN_MIN, globalMax)
        }
        return globalMax
    }

    private fun updateSippReadouts(
        sippDiaRow: Preference?,
        sippPeakRow: Preference?,
        sippIsfRow: Preference?,
        sippBasalRow: Preference?,
        sippMaxBasalRow: Preference?,
        sippDiagRow: Preference?
    ) {
        val prof = profileFunction.getProfile()
        val profDiaH = prof?.dia ?: hardLimits.minDia()
        val profPeakMin = preferences.get(IntKey.InsulinOrefPeak)
        val unitsMmol = (prof?.units == GlucoseUnit.MMOL)
        val profIsfMgdl = prof?.getIsfMgdl("InsulinOrefFreePeakPlugin")
        val profBasalUph = prof?.getBasal() ?: 0.0
        val prefMaxBasalUph = preferences.get(DoubleKey.ApsMaxBasal)

        val sippPkOn = SippPrefs.enablePk()
        val persisted = SippPrefs.loadState()

        // DIA
        sippDiaRow?.summary = if (sippPkOn) {
            val diaUpper = if (SippPrefs.allowDiaAbove9h()) 24f else 12f
            val curDiaH = (persisted?.diaH ?: sipp.current().diaH).coerceIn(4.5f, diaUpper)
            rh.gs(R.string.sipp_readout_instant_profile, "${fmt2(curDiaH.toDouble())} h", "${fmt2(profDiaH)} h")
        } else {
            rh.gs(R.string.sipp_readout_instant_na, "${fmt2(profDiaH)} h")
        }

        // Peak
        sippPeakRow?.summary = if (sippPkOn) {
            val fromSippMin = (persisted?.tPeakMin
                ?: (sipp.current().peakH?.times(60f)?.roundToInt() ?: profPeakMin))
            val curDiaForCap = (persisted?.diaH ?: sipp.current().diaH)
                .coerceIn(4.5f, if (SippPrefs.allowDiaAbove9h()) 24f else 12f)
            val logicalMax = peakLogicalMax(curDiaForCap, SippPrefs.allowDiaAbove9h())
            val curPeakMin = fromSippMin.coerceIn(GLOBAL_PEAK_MIN_MIN, logicalMax)
            rh.gs(R.string.sipp_readout_instant_profile, "$curPeakMin min", "$profPeakMin min")
        } else {
            rh.gs(R.string.sipp_readout_instant_na, "$profPeakMin min")
        }

        // ISF
        val usedIsfMgdl = SippPrefs.lastInstantIsfMgdl()
        val displayUsed = usedIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val displayUsedUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"
        val profileDisplay = profIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val profileUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"

        sippIsfRow?.summary = when {
            displayUsed != null && displayUsed > 0.0 -> {
                val profStr = if (profileDisplay != null) "${fmt2(profileDisplay)} $profileUnit" else "—"
                rh.gs(R.string.sipp_readout_instant_profile, "${fmt2(displayUsed)} $displayUsedUnit", profStr)
            }
            else -> {
                if (profileDisplay != null)
                    rh.gs(R.string.sipp_readout_instant_na, "${fmt2(profileDisplay)} $profileUnit")
                else
                    rh.gs(R.string.sipp_readout_both_na)
            }
        }

        // Instant Basal
        val instBasal: Double? = if (SippPrefs.enableBasal()) SippPrefs.lastInstantBasalUph() else null

        sippBasalRow?.summary = when {
            instBasal != null && instBasal > 0.0 ->
                rh.gs(R.string.sipp_readout_instant_profile, "${fmt2(instBasal)} U/h", "${fmt2(profBasalUph)} U/h")
            else ->
                rh.gs(R.string.sipp_readout_instant_na, "${fmt2(profBasalUph)} U/h")
        }

        // Max Basal
        val instMaxBasal: Double? = if (SippPrefs.enableMaxBasal()) SippPrefs.lastMaxBasalUph() else null

        sippMaxBasalRow?.summary = when {
            instMaxBasal != null && instMaxBasal > 0.0 ->
                rh.gs(R.string.sipp_readout_instant_pref, "${fmt2(instMaxBasal)} U/h", "${fmt2(prefMaxBasalUph)} U/h")
            else ->
                rh.gs(R.string.sipp_readout_instant_na_pref, "${fmt2(prefMaxBasalUph)} U/h")
        }

        // Diagnostics
        runCatching {
            val d = sipp.diagnostics()
            val rmseStr = String.format(Locale.getDefault(), "%.0f", d.rmse)
            val biasStr = String.format(Locale.getDefault(), "%+.0f", d.bias)
            val confStr = String.format(Locale.getDefault(), "%.2f", d.confidence)
            sippDiagRow?.summary = rh.gs(R.string.sipp_readout_diag, confStr, rmseStr, biasStr)
        }.onFailure {
            sippDiagRow?.summary = rh.gs(R.string.sipp_readout_diag_na)
        }
    }

    @Suppress("unused")
    private fun round2(v: Double) = floor(v * 100.0 + 0.5) / 100.0
    private fun fmt2(v: Double) = String.format(Locale.getDefault(), "%.2f", v)

    private fun safeUiRefresh() {
        sippDiaRowRef?.context?.let { SippPrefs.init(it) }
        updateSippReadouts(
            sippDiaRowRef, sippPeakRowRef, sippIsfRowRef, sippBasalRowRef, sippMaxBasalRowRef, sippDiagRowRef
        )
        updateActivityReadout()
    }

    // --- Time helpers (HH:MM) ---
    private fun toHHMM(minutesSinceMidnight: Int): String {
        val m = ((minutesSinceMidnight % 1440) + 1440) % 1440
        val h = m / 60
        val min = m % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, min)
    }

    private fun parseHHMM(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    override fun addPreferenceScreen(
        preferenceManager: androidx.preference.PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        if (requiredKey != null) return
        SippPrefs.init(context)

        // ===== SIPP section =====
        val sippCategory = PreferenceCategory(context).also {
            it.key = "insulin_sipp_settings"
            it.title = context.getString(R.string.sipp_category_title)
            it.initialExpandedChildrenCount = 0
        }
        parent.addPreference(sippCategory)

        val insulinTypePref = ListPreference(context).apply {
            key = "SIPP_insulin_archetype"
            title = context.getString(R.string.sipp_archetype_title)
            entries = arrayOf(
                context.getString(R.string.sipp_archetype_auto),
                context.getString(R.string.sipp_archetype_rapid),
                context.getString(R.string.sipp_archetype_fiasp),
                context.getString(R.string.sipp_archetype_lyumjev)
            )
            entryValues = arrayOf("AUTO", "RAPID", "FIASP", "LYUMJEV")
            val current = SippPrefs.insulinArchetype()
            value = if (entryValues.contains(current)) current else "AUTO"
            summary = when (value) {
                "RAPID"   -> context.getString(R.string.sipp_archetype_current_rapid)
                "FIASP"   -> context.getString(R.string.sipp_archetype_current_fiasp)
                "LYUMJEV" -> context.getString(R.string.sipp_archetype_current_lyumjev)
                else      -> context.getString(R.string.sipp_archetype_current_auto)
            }
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as? String) ?: "AUTO"
                SippPrefs.setInsulinArchetype(v)
                summary = when (v) {
                    "RAPID"   -> context.getString(R.string.sipp_archetype_current_rapid)
                    "FIASP"   -> context.getString(R.string.sipp_archetype_current_fiasp)
                    "LYUMJEV" -> context.getString(R.string.sipp_archetype_current_lyumjev)
                    else      -> context.getString(R.string.sipp_archetype_current_auto)
                }
                true
            }
        }
        sippCategory.addPreference(insulinTypePref)

        val sippPk = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_pk"
            title = context.getString(R.string.sipp_enable_pk_title)
            summary = context.getString(R.string.sipp_enable_pk_summary)
            isChecked = SippPrefs.enablePk()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnablePk(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippPk)

        val sippIsf = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_isf"
            title = context.getString(R.string.sipp_enable_isf_title)
            summary = context.getString(R.string.sipp_enable_isf_summary)
            isChecked = SippPrefs.enableIsf()
            isEnabled = SippPrefs.enablePk()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableIsf(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippIsf)

        val sippDiaExpert = SwitchPreferenceCompat(context).apply {
            key = "SIPP_allow_dia_above_9h"
            title = context.getString(R.string.sipp_allow_dia_above_9h_title)
            summary = context.getString(R.string.sipp_allow_dia_above_9h_summary)
            isChecked = SippPrefs.allowDiaAbove9h()
            isEnabled = SippPrefs.enablePk()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setAllowDiaAbove9h(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippDiaExpert)

        // ===== SIPP – Sleep (separate) =====
        val sleepCat = PreferenceCategory(context).apply {
            key = "sipp_sleep_settings"
            title = context.getString(R.string.sipp_sleep_category_title)
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(sleepCat)

        // ---- SIPP Sleep / Recovery ----
        val sleepMaster = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_sleep_recovery"
            title = context.getString(R.string.sipp_sleep_aware_dia_title)
            summary = context.getString(R.string.sipp_sleep_aware_dia_summary)
            isChecked = SippPrefs.enableSleepRecovery()
        }
        sleepCat.addPreference(sleepMaster)

        val autoToggle = SwitchPreferenceCompat(context).apply {
            key = "SIPP_auto_sleep_enabled"
            title = context.getString(R.string.sipp_auto_sleep_title)
            summary = context.getString(R.string.sipp_auto_sleep_summary)
            isChecked = SippPrefs.sleepAutoEnabled()
        }
        sleepCat.addPreference(autoToggle)

        val manualToggle = SwitchPreferenceCompat(context).apply {
            key = "SIPP_manual_sleep_enabled"
            title = context.getString(R.string.sipp_manual_sleep_title)
            summary = context.getString(R.string.sipp_manual_sleep_summary)
            isChecked = SippPrefs.manualSleepEnabled()
        }
        sleepCat.addPreference(manualToggle)

        // HH:MM editors (remain visible, enabled only when Manual is ON)
        val startPref = EditTextPreference(context).apply {
            key = "sipp_manual_sleep_start_hhmm"
            title = context.getString(R.string.sipp_sleep_start_title)
            val startMin = SippPrefs.manualSleepStartMin()
            text = toHHMM(startMin)
            summary = context.getString(R.string.sipp_sleep_time_summary, toHHMM(startMin), startMin)
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
            setOnPreferenceChangeListener { _, newValue ->
                val minutes = parseHHMM(newValue as String) ?: return@setOnPreferenceChangeListener false
                SippPrefs.setManualSleepStartMin(minutes)
                summary = context.getString(R.string.sipp_sleep_time_summary, toHHMM(minutes), minutes)
                true
            }
        }
        sleepCat.addPreference(startPref)

        val endPref = EditTextPreference(context).apply {
            key = "sipp_manual_sleep_end_hhmm"
            title = context.getString(R.string.sipp_sleep_end_title)
            val endMin = SippPrefs.manualSleepEndMin()
            text = toHHMM(endMin)
            summary = context.getString(R.string.sipp_sleep_time_summary, toHHMM(endMin), endMin)
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
            setOnPreferenceChangeListener { _, newValue ->
                val minutes = parseHHMM(newValue as String) ?: return@setOnPreferenceChangeListener false
                SippPrefs.setManualSleepEndMin(minutes)
                summary = context.getString(R.string.sipp_sleep_time_summary, toHHMM(minutes), minutes)
                true
            }
        }
        sleepCat.addPreference(endPref)

        // Single place to toggle enable/disable correctly
        fun refreshSleepUi() {
            val masterOn = SippPrefs.enableSleepRecovery()
            val manualOn = masterOn && SippPrefs.manualSleepEnabled() && !SippPrefs.sleepAutoEnabled()

            autoToggle.isEnabled = masterOn
            manualToggle.isEnabled = masterOn

            startPref.isEnabled = manualOn
            endPref.isEnabled = manualOn
        }

        // Wire mutual exclusivity & persist
        sleepMaster.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            SippPrefs.setEnableSleepRecovery(on)
            refreshSleepUi()
            true
        }

        autoToggle.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            // If Auto ON, force Manual OFF
            if (on) {
                SippPrefs.setManualSleepEnabled(false)
                manualToggle.isChecked = false
            }
            SippPrefs.setSleepAutoEnabled(on)
            refreshSleepUi()
            true
        }

        manualToggle.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            // If Manual ON, force Auto OFF
            if (on) {
                SippPrefs.setSleepAutoEnabled(false)
                autoToggle.isChecked = false
            }
            SippPrefs.setManualSleepEnabled(on)
            refreshSleepUi()
            true
        }

        // Initial enable/disable state
        refreshSleepUi()

        // ===== SIPP – Dosing rails =====
        val sippBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_basal"
            title = context.getString(R.string.sipp_enable_basal_title)
            summary = context.getString(R.string.sipp_enable_basal_summary)
            isChecked = SippPrefs.enableBasal()
            isEnabled = true
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableBasal(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippBasalToggle)

        val sippMaxBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_max_basal"
            title = context.getString(R.string.sipp_enable_max_basal_title)
            summary = context.getString(R.string.sipp_enable_max_basal_summary)
            isChecked = SippPrefs.enableMaxBasal()
            isEnabled = true
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableMaxBasal(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippMaxBasalToggle)

        // Site context
        val siteLocationPref = ListPreference(context).apply {
            key = "SIPP_site_location"
            title = context.getString(R.string.sipp_site_location_title)
            entries = arrayOf(
                context.getString(R.string.sipp_site_location_abdomen),
                context.getString(R.string.sipp_site_location_arm),
                context.getString(R.string.sipp_site_location_thigh)
            )
            entryValues = arrayOf("ABDOMEN", "ARM", "THIGH")
            val currentLoc = SippPrefs.siteLocation()
            value = currentLoc
            summary = context.getString(R.string.sipp_site_location_current, currentLoc)
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as? String) ?: "ABDOMEN"
                SippPrefs.setSiteLocation(v)
                summary = context.getString(R.string.sipp_site_location_current, v)
                true
            }
        }
        sippCategory.addPreference(siteLocationPref)

        val siteAgeEnabledPref = SwitchPreferenceCompat(context).apply {
            key = "SIPP_site_age_enabled"
            title = context.getString(R.string.sipp_site_age_enabled_title)
            summary = context.getString(R.string.sipp_site_age_enabled_summary)
            isChecked = SippPrefs.siteAgeEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setSiteAgeEnabled(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(siteAgeEnabledPref)

        val siteAgePref = EditTextPreference(context).apply {
            key = "SIPP_site_age_h"
            title = context.getString(R.string.sipp_site_age_title)
            dialogTitle = context.getString(R.string.sipp_site_age_dialog)
            val currentAge = SippPrefs.siteAgeH()
            text = currentAge
            summary = context.getString(R.string.sipp_site_age_current, currentAge)
            isEnabled = siteAgeEnabledPref.isChecked
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as? String) ?: "1"
                SippPrefs.setSiteAgeH(v)
                summary = context.getString(R.string.sipp_site_age_current, v)
                true
            }
        }
        sippCategory.addPreference(siteAgePref)

        // Live readouts
        val sippDiaRow = Preference(context).apply { key = "sipp_readout_dia"; title = context.getString(R.string.sipp_readout_dia); isSelectable = false }
        val sippPeakRow = Preference(context).apply { key = "sipp_readout_peak"; title = context.getString(R.string.sipp_readout_peak); isSelectable = false }
        val sippIsfRow = Preference(context).apply { key = "sipp_readout_isf"; title = context.getString(R.string.sipp_readout_isf); isSelectable = false }
        val sippBasalRow = Preference(context).apply { key = "sipp_readout_basal"; title = context.getString(R.string.sipp_readout_basal); isSelectable = false }
        val sippMaxBasalRow = Preference(context).apply { key = "sipp_readout_max_basal"; title = context.getString(R.string.sipp_readout_max_basal); isSelectable = false }
        val sippDiagRow = Preference(context).apply { key = "SIPP_readout_diag"; title = context.getString(R.string.sipp_readout_verification); isSelectable = false }

        sippCategory.addPreference(sippDiaRow)
        sippCategory.addPreference(sippPeakRow)
        sippCategory.addPreference(sippIsfRow)
        sippCategory.addPreference(sippBasalRow)
        sippCategory.addPreference(sippMaxBasalRow)
        sippCategory.addPreference(sippDiagRow)

        sippDiaRowRef = sippDiaRow
        sippPeakRowRef = sippPeakRow
        sippIsfRowRef = sippIsfRow
        sippBasalRowRef = sippBasalRow
        sippMaxBasalRowRef = sippMaxBasalRow
        sippDiagRowRef = sippDiagRow

        updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow, sippBasalRow, sippMaxBasalRow, sippDiagRow)

        // Activity signals
        addSippActivityPrefs(parent, context)
    }

    // ---------- SIPP Activity Signals UI ----------
    private fun addSippActivityPrefs(parent: PreferenceScreen, context: Context) {
        SippPrefs.init(context)
        val category = PreferenceCategory(context).apply {
            key = "sipp_activity_signals"
            title = context.getString(R.string.sipp_activity_signals_category_title)
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(category)

        val master = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_activity_signals"
            title = context.getString(R.string.sipp_activity_signals_title)
            summary = context.getString(R.string.sipp_activity_signals_summary)
            isChecked = SippPrefs.enableActivityFusion()
            setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                SippPrefs.setEnableActivityFusion(on)
                hrPrefRef?.isEnabled = on
                stepsPrefRef?.isEnabled = on
                updateActivityReadout()
                true
            }
        }

        val hrPref = SwitchPreferenceCompat(context).apply {
            key = "SIPP_use_hr"
            title = context.getString(R.string.sipp_use_hr_title)
            summary = context.getString(R.string.sipp_use_hr_summary)
            isChecked = SippPrefs.useHr()
            isEnabled = SippPrefs.enableActivityFusion()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setUseHr(newValue as Boolean)
                updateActivityReadout()
                true
            }
        }

        val stepsPref = SwitchPreferenceCompat(context).apply {
            key = "SIPP_use_steps"
            title = context.getString(R.string.sipp_use_steps_title)
            summary = context.getString(R.string.sipp_use_steps_summary)
            isChecked = SippPrefs.useSteps()
            isEnabled = SippPrefs.enableActivityFusion()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setUseSteps(newValue as Boolean)
                updateActivityReadout()
                true
            }
        }

        val activityReadout = Preference(context).apply {
            key = "sipp_readout_activity"
            title = context.getString(R.string.sipp_activity_evidence_title)
            isSelectable = false
        }

        hrPrefRef = hrPref
        stepsPrefRef = stepsPref
        activityReadoutRef = activityReadout

        category.addPreference(master)
        category.addPreference(hrPref)
        category.addPreference(stepsPref)
        category.addPreference(activityReadout)

        updateActivityReadout()
    }

    private fun updateActivityReadout() {
        val row = activityReadoutRef ?: return
        runCatching {
            val snap = sipp.activitySnapshot()
            val fusionOn = SippPrefs.enableActivityFusion()
            val hrToggleOn = SippPrefs.useHr()
            val stepsToggleOn = SippPrefs.useSteps()
            val hr = snap.hrBpm?.toString() ?: "—"
            val spm = snap.stepsPerMin?.toString() ?: "—"
            val sustain = snap.sustainedActiveMin.toString()
            val actFlags = buildString {
                val flags = mutableListOf<String>()
                if (snap.hrActive) flags += "HR"
                if (snap.cadenceActive) flags += "Steps"
                if (snap.hintActive) flags += "Hint"
                append(if (flags.isEmpty()) "—" else flags.joinToString("+"))
            }
            val isfPct = ((snap.isfScaleApplied - 1.0f) * 100f)
            val isfStr = if (isfPct >= 0.05f) String.format(Locale.getDefault(), "+%.0f%%", isfPct) else "0%"
            val peakStr = if (snap.peakShiftMin > 0) "+${snap.peakShiftMin} min" else "0 min"
            val fusionStr = if (fusionOn) "ON" else "OFF"
            val hrToggleStr = if (hrToggleOn) "ON" else "OFF"
            val stepsToggleStr = if (stepsToggleOn) "ON" else "OFF"
            row.summary = rh.gs(
                R.string.sipp_activity_summary,
                fusionStr, hrToggleStr, stepsToggleStr, hr, spm, sustain, actFlags, isfStr, peakStr
            )
        }.onFailure {
            row.summary = rh.gs(R.string.sipp_activity_summary_na)
        }
    }

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun isNowInWindow(startMin: Int, endMin: Int): Boolean {
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return if (startMin <= endMin) nowMin in startMin..endMin else nowMin >= startMin || nowMin <= endMin
    }
}

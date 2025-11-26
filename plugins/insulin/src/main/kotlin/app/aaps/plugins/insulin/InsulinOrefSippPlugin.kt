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
    private val sipp: SentinelPkPdController
) : InsulinOrefBasePlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

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
                (!snap.hrActive && !snap.cadenceActive && snap.sustainedActiveMin >= 20)
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
            "Instant (SIPP): ${fmt2(curDiaH.toDouble())} h   |   Profile: ${fmt2(profDiaH)} h"
        } else {
            "Instant (SIPP): —   |   Profile: ${fmt2(profDiaH)} h"
        }

        // Peak
        sippPeakRow?.summary = if (sippPkOn) {
            val fromSippMin = (persisted?.tPeakMin
                ?: (sipp.current().peakH?.times(60f)?.roundToInt() ?: profPeakMin))
            val curDiaForCap = (persisted?.diaH ?: sipp.current().diaH)
                .coerceIn(4.5f, if (SippPrefs.allowDiaAbove9h()) 24f else 12f)
            val logicalMax = peakLogicalMax(curDiaForCap, SippPrefs.allowDiaAbove9h())
            val curPeakMin = fromSippMin.coerceIn(GLOBAL_PEAK_MIN_MIN, logicalMax)
            "Instant (SIPP): $curPeakMin min   |   Profile: $profPeakMin min"
        } else {
            "Instant (SIPP): —   |   Profile: $profPeakMin min"
        }

        // ISF
        val usedIsfMgdl = SippPrefs.lastInstantIsfMgdl()
        val displayUsed = usedIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val displayUsedUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"
        val profileDisplay = profIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val profileUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"

        sippIsfRow?.summary = when {
            displayUsed != null && displayUsed > 0.0 -> {
                val left = "Instant (SIPP): ${fmt2(displayUsed)} $displayUsedUnit"
                val right = if (profileDisplay != null)
                    " | Profile: ${fmt2(profileDisplay)} $profileUnit"
                else " | Profile: —"
                left + right
            }
            else -> {
                if (profileDisplay != null)
                    "Instant (SIPP): — | Profile: ${fmt2(profileDisplay)} $profileUnit"
                else
                    "Instant (SIPP): — | Profile: —"
            }
        }

        // Instant Basal
        val instBasal: Double? = runCatching {
            if (!SippPrefs.enableBasal()) null
            else {
                val isfMgdl = usedIsfMgdl ?: return@runCatching null
                if (isfMgdl <= 0.0) null
                else {
                    val tddEst = 1800.0 / isfMgdl
                    round2(0.45 * tddEst / 24.0)
                }
            }
        }.getOrNull()

        sippBasalRow?.summary = when {
            instBasal != null && instBasal > 0.0 ->
                "Instant (SIPP): ${fmt2(instBasal)} U/h   |   Profile: ${fmt2(profBasalUph)} U/h"
            else ->
                "Instant (SIPP): —   |   Profile: ${fmt2(profBasalUph)} U/h"
        }

        // Max Basal
        val instMaxBasal: Double? = runCatching {
            if (!SippPrefs.enableMaxBasal()) null
            else {
                val isfMgdl = usedIsfMgdl ?: return@runCatching null
                if (isfMgdl <= 0.0 || prof == null) null
                else {
                    val tddEst = 1800.0 / isfMgdl
                    val instantBasal = 0.45 * tddEst / 24.0
                    val scheduled = prof.getBasal()
                    val fromMultiplier = scheduled * 1.8
                    val fromInstant = instantBasal * 3.0
                    val fromDaily = prof.getMaxDailyBasal() * preferences.get(DoubleKey.ApsMaxDailyMultiplier)
                    val unclamped = maxOf(fromMultiplier, fromInstant, fromDaily)
                    round2(unclamped.coerceAtMost(hardLimits.maxBasal()))
                }
            }
        }.getOrNull()

        sippMaxBasalRow?.summary = when {
            instMaxBasal != null && instMaxBasal > 0.0 ->
                "Instant (SIPP): ${fmt2(instMaxBasal)} U/h   |   Pref: ${fmt2(prefMaxBasalUph)} U/h"
            else ->
                "Instant (SIPP): —   |   Pref: ${fmt2(prefMaxBasalUph)} U/h"
        }

        // Diagnostics
        runCatching {
            val d = sipp.diagnostics()
            val rmseStr = String.format(Locale.getDefault(), "%.0f", d.rmse)
            val biasStr = String.format(Locale.getDefault(), "%+.0f", d.bias)
            val confStr = String.format(Locale.getDefault(), "%.2f", d.confidence)
            sippDiagRow?.summary = "Confidence: $confStr  |  RMSE: $rmseStr  |  Bias: $biasStr"
        }.onFailure {
            sippDiagRow?.summary = "Confidence: —  |  RMSE: —  |  Bias: —"
        }
    }

    private fun round2(v: Double) = floor(v * 100.0 + 0.5) / 100.0
    private fun fmt2(v: Double) = String.format(Locale.getDefault(), "%.2f", v)

    private fun safeUiRefresh() {
        updateSippReadouts(
            sippDiaRowRef, sippPeakRowRef, sippIsfRowRef, sippBasalRowRef, sippMaxBasalRowRef, sippDiagRowRef
        )
        updateActivityReadout()
    }

    // --- Sleep inputs enablement helper ---
    @Suppress("unused")
    private fun updateSleepInputsEnabled(
        manualToggle: SwitchPreferenceCompat,
        startPref: EditTextPreference,
        endPref: EditTextPreference
    ) {
        val enabled = manualToggle.isChecked && SippPrefs.enableSleepRecovery() && SippPrefs.enablePk()
        startPref.isEnabled = enabled
        endPref.isEnabled = enabled
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
            it.title = "SIPP (Sentinel Instant PK/PD)"
            it.initialExpandedChildrenCount = 0
        }
        parent.addPreference(sippCategory)

        val insulinTypePref = ListPreference(context).apply {
            key = "sipp_insulin_archetype"
            title = "Insulin archetype for SIPP seeding"
            entries = arrayOf("AUTO (use current preset)", "Rapid-acting (Humalog / NovoRapid)", "Ultra-rapid (Fiasp)", "Lyumjev")
            entryValues = arrayOf("AUTO", "RAPID", "FIASP", "LYUMJEV")
            val current = SippPrefs.insulinArchetype()
            value = if (entryValues.contains(current)) current else "AUTO"
            summary = when (value) {
                "RAPID"   -> "Current: Rapid-acting (Humalog / NovoRapid)"
                "FIASP"   -> "Current: Ultra-rapid (Fiasp)"
                "LYUMJEV" -> "Current: Lyumjev"
                else      -> "Current: AUTO (use current preset)"
            }
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as? String) ?: "AUTO"
                SippPrefs.setInsulinArchetype(v)
                summary = when (v) {
                    "RAPID"   -> "Current: Rapid-acting (Humalog / NovoRapid)"
                    "FIASP"   -> "Current: Ultra-rapid (Fiasp)"
                    "LYUMJEV" -> "Current: Lyumjev"
                    else      -> "Current: AUTO (use current preset)"
                }
                true
            }
        }
        sippCategory.addPreference(insulinTypePref)

        val sippPk = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_pk"
            title = "Enable SIPP: PK (DIA & Peak)"
            summary = "Let SIPP auto-tune DIA/Peak with safety rails."
            isChecked = SippPrefs.enablePk()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnablePk(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippPk)

        val sippIsf = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_isf"
            title = "Enable SIPP: ISF"
            summary = "Uses Instant ISF (exp-weighted TDD)."
            isChecked = SippPrefs.enableIsf()
            isEnabled = SippPrefs.enablePk()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableIsf(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippIsf)

        val sippDiaExpert = SwitchPreferenceCompat(context).apply {
            key = "sipp_allow_dia_above_9h"
            title = "Allow DIA > 9 h (expert)"
            summary = "Allows SIPP to extend DIA up to 24 h for slow sites/stacking."
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
            title = "SIPP – Sleep"
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(sleepCat)

        // ---- SIPP Sleep / Recovery ----
        val sleepMaster = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_sleep_recovery"
            title = "Sleep-aware DIA"
            summary = "Apply gentler DIA ceiling during sleep."
            isChecked = SippPrefs.enableSleepRecovery()
        }
        sleepCat.addPreference(sleepMaster)

        val autoToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_sleep_auto_enabled"
            title = "Auto sleep (watch HR & steps)"
            summary = "Detect sleep from low HR, no cadence, sustained inactivity."
            isChecked = SippPrefs.sleepAutoEnabled()
        }
        sleepCat.addPreference(autoToggle)

        val manualToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_manual_sleep_enabled"
            title = "Manual sleep window"
            summary = "Use fixed times when no wearable sleep signal is present."
            isChecked = SippPrefs.manualSleepEnabled()
        }
        sleepCat.addPreference(manualToggle)

        // HH:MM editors (remain visible, enabled only when Manual is ON)
        val startPref = EditTextPreference(context).apply {
            key = "sipp_manual_sleep_start_hhmm"
            title = "Sleep window start (HH:MM)"
            val startMin = SippPrefs.manualSleepStartMin()
            text = toHHMM(startMin)
            summary = "Current: ${toHHMM(startMin)} (${startMin} min)"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
            setOnPreferenceChangeListener { _, newValue ->
                val minutes = parseHHMM(newValue as String) ?: return@setOnPreferenceChangeListener false
                SippPrefs.setManualSleepStartMin(minutes)
                summary = "Current: ${toHHMM(minutes)} (${minutes} min)"
                true
            }
        }
        sleepCat.addPreference(startPref)

        val endPref = EditTextPreference(context).apply {
            key = "sipp_manual_sleep_end_hhmm"
            title = "Sleep window end (HH:MM)"
            val endMin = SippPrefs.manualSleepEndMin()
            text = toHHMM(endMin)
            summary = "Current: ${toHHMM(endMin)} (${endMin} min)"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
            setOnPreferenceChangeListener { _, newValue ->
                val minutes = parseHHMM(newValue as String) ?: return@setOnPreferenceChangeListener false
                SippPrefs.setManualSleepEndMin(minutes)
                summary = "Current: ${toHHMM(minutes)} (${minutes} min)"
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
            key = "sipp_enable_basal"
            title = "Apply SIPP Instant Basal"
            summary = "Use SIPP’s calculated basal (from Used ISF) as the current basal input."
            isChecked = SippPrefs.enableBasal()
            isEnabled = true
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableBasal(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(sippBasalToggle)

        val sippMaxBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_max_basal"
            title = "Apply SIPP Max Temp Basal cap"
            summary = "Limit temp basals to SIPP’s suggested maximum."
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
            key = "sipp_site_location"
            title = "Site Location"
            entries = arrayOf("Abdomen", "Arm", "Thigh")
            entryValues = arrayOf("ABDOMEN", "ARM", "THIGH")
            val currentLoc = SippPrefs.siteLocation()
            value = currentLoc
            summary = "Current: $currentLoc"
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as? String) ?: "ABDOMEN"
                SippPrefs.setSiteLocation(v)
                summary = "Current: $v"
                true
            }
        }
        sippCategory.addPreference(siteLocationPref)

        val siteAgeEnabledPref = SwitchPreferenceCompat(context).apply {
            key = "sipp_site_age_enabled"
            title = "Use Site Age Effect (advanced)"
            summary = "OFF recommended for Medtrum Nano."
            isChecked = SippPrefs.siteAgeEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setSiteAgeEnabled(newValue as Boolean)
                true
            }
        }
        sippCategory.addPreference(siteAgeEnabledPref)

        val siteAgePref = EditTextPreference(context).apply {
            key = "sipp_site_age_h"
            title = "Site Age (hours)"
            dialogTitle = "Enter hours since insertion"
            val currentAge = SippPrefs.siteAgeH()
            text = currentAge
            summary = "Current: $currentAge"
            isEnabled = siteAgeEnabledPref.isChecked
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as? String) ?: "1"
                SippPrefs.setSiteAgeH(v)
                summary = "Current: $v"
                true
            }
        }
        sippCategory.addPreference(siteAgePref)

        // Live readouts
        val sippDiaRow = Preference(context).apply { key = "sipp_readout_dia"; title = "DIA"; isSelectable = false }
        val sippPeakRow = Preference(context).apply { key = "sipp_readout_peak"; title = "Peak Time"; isSelectable = false }
        val sippIsfRow = Preference(context).apply { key = "sipp_readout_isf"; title = "ISF"; isSelectable = false }
        val sippBasalRow = Preference(context).apply { key = "sipp_readout_basal"; title = "Basal (U/h)"; isSelectable = false }
        val sippMaxBasalRow = Preference(context).apply { key = "sipp_readout_max_basal"; title = "Max Basal (U/h)"; isSelectable = false }
        val sippDiagRow = Preference(context).apply { key = "SIPP_readout_diag"; title = "Verification"; isSelectable = false }

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

        // Activity fusion
        addSippActivityPrefs(parent, context)
    }

    // ---------- SIPP Activity Fusion UI ----------
    private fun addSippActivityPrefs(parent: PreferenceScreen, context: Context) {
        SippPrefs.init(context)
        val category = PreferenceCategory(context).apply {
            key = "sipp_activity_fusion"
            title = "SIPP – Activity fusion"
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(category)

        val master = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_activity_fusion_ui"
            title = "Use activity fusion (HR & steps)"
            summary = "Small ISF weakening (+3–12%) and up to +10 min peak shift; DIA never shortened."
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
            key = "sipp_use_hr_ui"
            title = "Use heart-rate"
            summary = "Treat HR ≥100 bpm as activity (adds small, capped ISF weakening)."
            isChecked = SippPrefs.useHr()
            isEnabled = SippPrefs.enableActivityFusion()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setUseHr(newValue as Boolean)
                updateActivityReadout()
                true
            }
        }

        val stepsPref = SwitchPreferenceCompat(context).apply {
            key = "sipp_use_steps_ui"
            title = "Use steps / cadence"
            summary = "Active when ≥60 steps/min for ≥5 min."
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
            title = "Activity evidence"
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
            val sustain = snap.sustainedActiveMin
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
            val togglesStr = "hr=" + (if (hrToggleOn) "ON" else "OFF") + ", steps=" + (if (stepsToggleOn) "ON" else "OFF")
            row.summary = "Fusion: " + (if (fusionOn) "ON" else "OFF") +
                "  |  Toggles: $togglesStr  |  HR: $hr bpm  |  SPM: $spm  |  Sustained: ${sustain}m  |  Active: $actFlags  |  ISF: $isfStr  |  Peak: $peakStr"
        }.onFailure {
            row.summary = "Fusion: —  |  Toggles: —  |  HR: —  |  SPM: —  |  Sustained: —  |  Active: —  |  ISF: —  |  Peak: —"
        }
    }

    private fun isNowInWindow(startMin: Int, endMin: Int): Boolean {
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return if (startMin <= endMin) nowMin in startMin..endMin else nowMin >= startMin || nowMin <= endMin
    }
}

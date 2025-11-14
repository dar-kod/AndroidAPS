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
import app.aaps.core.keys.BooleanKey
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Oref Free-Peak insulin with SIPP PK handoff (DIA/Peak),
 * live ISF readout, and live-derived Basal / Max Basal readouts.
 * Activity evidence (HR/SPM) with tiny ISF and peak hints.
 */
@Singleton
class InsulinOrefFreePeakPlugin @Inject constructor(
    private val preferences: Preferences,
    rHelp: ResourceHelper,
    profFunc: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction,
    private val sipp: SentinelPkPdController
) : InsulinOrefBasePlugin(
    rHelp, profFunc, rxBus, aapsLogger, config, hardLimits, uiInteraction
) {

    private companion object {

        // Global guards
        private const val GLOBAL_PEAK_MIN_MIN = 45
        private const val GLOBAL_PEAK_MAX_DEFAULT = 210
        private const val GLOBAL_PEAK_MAX_EXTENDED = 240  // when "Allow DIA > 9h" is enabled

        // Ramp anchors for the saturating cap
        private const val RAMP_START_DIA_H = 6f
        private const val RAMP_END_DIA_H = 12f
        private const val CAP_AT_6H_MIN = 120
    }

    // row refs for live refresh
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
        super.onStop(); busSubs.clear()
    }

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_FREE_PEAK
    override val friendlyName get(): String = rh.gs(R.string.free_peak_oref)

    override fun configuration(): JSONObject =
        JSONObject()
            .put(IntKey.InsulinOrefPeak, preferences)
            .put("SIPP_CONFIG", SippPrefs.packToJson())

    override fun applyConfiguration(configuration: JSONObject) {
        configuration.store(IntKey.InsulinOrefPeak, preferences)
        SippPrefs.applyFromJson(configuration.optJSONObject("SIPP_CONFIG"))
    }

    override fun commentStandardText(): String = rh.gs(R.string.insulin_peak_time) + ": " + peak

    /** DIA from SIPP when PK enabled; otherwise Profile DIA. */
    override val userDefinedDia: Double
        get() {
            val profileDia = profileFunction.getProfile()?.dia ?: hardLimits.minDia()
            if (!SippPrefs.enablePk()) return profileDia
            val persisted = SippPrefs.loadState()
            val diaH = (persisted?.diaH ?: sipp.current().diaH).toDouble()
            val upper = if (SippPrefs.allowDiaAbove9h()) 24.0 else 9.0
            return diaH.coerceIn(4.5, upper)
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

    /** Monotone, saturating cap for peak time as DIA grows. */
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

    /** Live refresh (derived from *used* ISF, not persisted snapshots). */
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
            String.format(Locale.getDefault(), "Instant (SIPP): %.2f h   |   Profile: %.2f h", curDiaH, profDiaH)
        } else {
            String.format(Locale.getDefault(), "Instant (SIPP): —   |   Profile: %.2f h", profDiaH)
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

        // ISF (display only; mg/dL→mmol conversion here)
        val usedIsfMgdl = SippPrefs.lastInstantIsfMgdl()  // mg/dL/U actually used by SMB
        val displayUsed = usedIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val displayUsedUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"
        val profileDisplay = profIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val profileUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"

        sippIsfRow?.summary = when {
            displayUsed != null && displayUsed > 0.0 -> {
                val left = String.format(Locale.getDefault(), "Instant (SIPP): %.2f %s", displayUsed, displayUsedUnit)
                val right = if (profileDisplay != null)
                    String.format(Locale.getDefault(), " | Profile: %.2f %s", profileDisplay, profileUnit)
                else " | Profile: —"
                left + right
            }
            else -> {
                if (profileDisplay != null)
                    String.format(Locale.getDefault(), "Instant (SIPP): — | Profile: %.2f %s", profileDisplay, profileUnit)
                else
                    "Instant (SIPP): — | Profile: —"
            }
        }

        // ===== Derive Instant Basal & Max Basal LIVE from used ISF =====
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
                String.format(Locale.getDefault(), "Derived (SIPP): %.2f U/h   |   Profile: %.2f U/h", instBasal, profBasalUph)
            else ->
                String.format(Locale.getDefault(), "Derived (SIPP): —   |   Profile: %.2f U/h", profBasalUph)
        }

        // <<< THIS BLOCK MUST EXIST so the summary below compiles >>>
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
                    val unclamped = max(fromMultiplier, max(fromInstant, fromDaily))
                    round2(unclamped.coerceAtMost(hardLimits.maxBasal()))
                }
            }
        }.getOrNull()

        sippMaxBasalRow?.summary = when {
            instMaxBasal != null && instMaxBasal > 0.0 ->
                String.format(Locale.getDefault(), "Derived (SIPP): %.2f U/h   |   Pref: %.2f U/h", instMaxBasal, prefMaxBasalUph)
            else ->
                String.format(Locale.getDefault(), "Derived (SIPP): —   |   Pref: %.2f U/h", prefMaxBasalUph)
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

    private fun round2(v: Double) = kotlin.math.floor(v * 100.0 + 0.5) / 100.0

    private fun safeUiRefresh() {
        updateSippReadouts(
            sippDiaRowRef, sippPeakRowRef, sippIsfRowRef, sippBasalRowRef, sippMaxBasalRowRef, sippDiagRowRef
        )
        updateActivityReadout()
    }

    init {
        pluginDescription
            .pluginIcon(R.drawable.ic_insulin)
            .pluginName(R.string.free_peak_oref)
            .preferencesId(PluginDescription.PREFERENCE_SCREEN)
            .description(R.string.description_insulin_free_peak)
    }

    override fun addPreferenceScreen(
        preferenceManager: androidx.preference.PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        if (requiredKey != null) return
        SippPrefs.init(context)

        // ===== Free-Peak section =====
        val fpCat = PreferenceCategory(context).apply {
            key = "insulin_free_peak_settings"
            title = rh.gs(R.string.insulin_oref_peak)
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(fpCat)
        fpCat.addPreference(
            app.aaps.core.validators.preferences.AdaptiveIntPreference(
                ctx = context, intKey = IntKey.InsulinOrefPeak, title = R.string.insulin_peak_time
            )
        )

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
        }
        sippCategory.addPreference(insulinTypePref)

        val sippPk = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_pk"
            title = "Enable SIPP: PK (DIA & Peak)"
            summary = "Let SIPP auto-tune DIA/Peak with safety rails."
            isChecked = SippPrefs.enablePk()
        }
        sippCategory.addPreference(sippPk)

        val sippIsf = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_isf"
            title = "Enable SIPP: ISF"
            summary = "Uses Instant ISF (exp-weighted TDD). Turning ON will turn Dynamic Sensitivity OFF."
            isChecked = SippPrefs.enableIsf()
            isEnabled = SippPrefs.enablePk() && !preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        }
        sippCategory.addPreference(sippIsf)

        val sippDiaExpert = SwitchPreferenceCompat(context).apply {
            key = "sipp_allow_dia_above_9h"
            title = "Allow DIA > 9 h (expert)"
            summary = "Allows SIPP to extend DIA up to 24 h for slow sites/stacking."
            isChecked = SippPrefs.allowDiaAbove9h()
            isEnabled = SippPrefs.enablePk()
        }
        sippCategory.addPreference(sippDiaExpert)

        val sippBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_basal"
            title = "Apply SIPP Instant Basal"
            summary = "Use SIPP’s calculated basal (from Used ISF) as the current basal input."
            isChecked = SippPrefs.enableBasal()
            isEnabled = true
        }
        sippCategory.addPreference(sippBasalToggle)

        val sippMaxBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_max_basal"
            title = "Apply SIPP Max Temp Basal cap"
            summary = "Limit temp basals to SIPP’s suggested maximum."
            isChecked = SippPrefs.enableMaxBasal()
            isEnabled = true
        }
        sippCategory.addPreference(sippMaxBasalToggle)

        val siteLocationPref = ListPreference(context).apply {
            key = "sipp_site_location"
            title = "Site Location"
            entries = arrayOf("Abdomen", "Arm", "Thigh")
            entryValues = arrayOf("ABDOMEN", "ARM", "THIGH")
            val currentLoc = SippPrefs.siteLocation()
            value = currentLoc
            summary = "Current: $currentLoc"
        }
        sippCategory.addPreference(siteLocationPref)

        val siteAgeEnabledPref = SwitchPreferenceCompat(context).apply {
            key = "sipp_site_age_enabled"
            title = "Use Site Age Effect (advanced)"
            summary = "OFF recommended for Medtrum Nano."
            isChecked = SippPrefs.siteAgeEnabled()
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
        }
        sippCategory.addPreference(siteAgePref)

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

        sippDiaRowRef = sippDiaRow; sippPeakRowRef = sippPeakRow; sippIsfRowRef = sippIsfRow
        sippBasalRowRef = sippBasalRow; sippMaxBasalRowRef = sippMaxBasalRow; sippDiagRowRef = sippDiagRow

        updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow, sippBasalRow, sippMaxBasalRow, sippDiagRow)

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

    /** Builds concise summary of HR/SPM evidence and tiny effects applied. */
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
}

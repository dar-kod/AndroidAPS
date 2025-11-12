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
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Oref Free-Peak insulin with SIPP PK handoff (DIA/Peak),
 * live ISF readout, and live-derived Basal / Max Basal readouts.
 * Sleep-aware DIA ceiling and Activity fusion (HR/SPM hints).
 */
@Singleton
class InsulinOrefFreePeakPlugin @Inject constructor(
    private val preferences: Preferences,
    rHelp: ResourceHelper,
    profFunc: ProfileFunction,
    rxBusParam: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction,
    private val sipp: SentinelPkPdController
) : InsulinOrefBasePlugin(
    rHelp, profFunc, rxBusParam, aapsLogger, config, hardLimits, uiInteraction
) {

    private companion object {

        // Global caps (peak)
        private const val GLOBAL_PEAK_MIN_MIN = 45
        private const val GLOBAL_PEAK_MAX_DEFAULT = 210
        private const val GLOBAL_PEAK_MAX_EXTENDED = 240

        // Ramp anchors for peak cap as DIA grows
        private const val RAMP_START_DIA_H = 6f
        private const val RAMP_END_DIA_H = 12f
        private const val CAP_AT_6H_MIN = 120
    }

    // live row refs
    private var sippDiaRowRef: Preference? = null
    private var sippPeakRowRef: Preference? = null
    private var sippIsfRowRef: Preference? = null
    private var sippBasalRowRef: Preference? = null
    private var sippMaxBasalRowRef: Preference? = null
    private var sippDiagRowRef: Preference? = null
    private var activityReadoutRef: Preference? = null
    private var hrPrefRef: SwitchPreferenceCompat? = null
    private var stepsPrefRef: SwitchPreferenceCompat? = null
    private var sleepManualStartRef: EditTextPreference? = null
    private var sleepManualEndRef: EditTextPreference? = null

    private val busSubs = CompositeDisposable()

    override fun onStart() {
        super.onStart()
        runCatching {
            busSubs.add(
                rxBus.toObservable(EventNewBG::class.java)
                    .subscribe({ _: EventNewBG -> safeUiRefresh() }, { _: Throwable -> })
            )
            busSubs.add(
                rxBus.toObservable(EventAPSCalculationFinished::class.java)
                    .subscribe({ _: EventAPSCalculationFinished -> safeUiRefresh() }, { _: Throwable -> })
            )
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

    // ---------- Sleep detection helpers ----------
    private fun isManualSleepNow(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        return if (startMin <= endMin) {
            nowMin in startMin..endMin
        } else {
            // window spans midnight
            nowMin >= startMin || nowMin <= endMin
        }
    }

    private fun nowMinutesSinceMidnight(): Int {
        val now = System.currentTimeMillis()
        val mins = (now / 60000L % (24 * 60)).toInt()
        return if (mins < 0) mins + 24 * 60 else mins
    }

    private fun sleepActiveNow(): Boolean {
        if (!SippPrefs.enableSleepRecovery()) return false
        // Auto: rely on SIPP Activity fusion hints (no HR/cadence ⇒ likely rest)
        val autoRest = if (SippPrefs.enableActivityFusion()) {
            val snap = sipp.activitySnapshot()
            (!snap.hrActive && !snap.cadenceActive)
        } else false

        // Manual window (for users without watch)
        val manualOn = SippPrefs.manualSleepEnabled()
        val manualNow = if (manualOn) {
            val start = SippPrefs.manualSleepStartMin()
            val end = SippPrefs.manualSleepEndMin()
            val nowMin = nowMinutesSinceMidnight()
            isManualSleepNow(nowMin, start, end)
        } else false

        return autoRest || manualNow
    }

    // ---------- DIA/peak exposure ----------
    /** DIA from SIPP when PK enabled; otherwise Profile DIA. Sleep-aware ceiling, never shortened. */
    override val userDefinedDia: Double
        get() {
            val profileDia = profileFunction.getProfile()?.dia ?: hardLimits.minDia()
            if (!SippPrefs.enablePk()) return profileDia

            val persisted = SippPrefs.loadState()
            val diaH = (persisted?.diaH ?: sipp.current().diaH).toDouble()
            val baseUpper = if (SippPrefs.allowDiaAbove9h()) 24.0 else 9.0
            // During Sleep & Recovery we allow using the configured extended ceiling (does not force >9h by itself).
            val upper = baseUpper
            return diaH.coerceIn(4.5, upper)
        }

    /** Peak (minutes) from SIPP when PK enabled; otherwise preference. Monotone with DIA. */
    override val peak: Int
        get() = if (SippPrefs.enablePk()) {
            val profMin = preferences.get(IntKey.InsulinOrefPeak)
            val persisted = SippPrefs.loadState()

            val diaUpper = if (SippPrefs.allowDiaAbove9h()) 24f else 12f
            val curDiaH = (persisted?.diaH ?: sipp.current().diaH).coerceIn(4.5f, diaUpper)

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

    // ---------- Live readouts ----------
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

        val sleepFlag = if (SippPrefs.enableSleepRecovery()) (if (sleepActiveNow()) " (sleep)" else " (awake)") else ""

        // DIA
        sippDiaRow?.summary = if (sippPkOn) {
            val diaUpper = if (SippPrefs.allowDiaAbove9h()) 24f else 12f
            val curDiaH = (persisted?.diaH ?: sipp.current().diaH).coerceIn(4.5f, diaUpper)
            String.format(Locale.getDefault(), "Instant (SIPP%s): %.2f h   |   Profile: %.2f h", sleepFlag, curDiaH, profDiaH)
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
            "Instant (SIPP$sleepFlag): $curPeakMin min   |   Profile: $profPeakMin min"
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

        // Derived Instant Basal
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

        // Derived Max Basal (independent view)
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
                    val maxOfCandidates = maxOf(fromMultiplier, fromInstant, fromDaily)
                    round2(maxOfCandidates.coerceAtMost(hardLimits.maxBasal()))
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

    private fun round2(v: Double) = floor(v * 100.0 + 0.5) / 100.0

    private fun safeUiRefresh() {
        updateSippReadouts(
            sippDiaRowRef, sippPeakRowRef, sippIsfRowRef, sippBasalRowRef, sippMaxBasalRowRef, sippDiagRowRef
        )
        updateActivityReadout()
        updateSleepSummaries()
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
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setInsulinArchetype(newValue as String); true
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
                safeUiRefresh()
                true
            }
        }
        sippCategory.addPreference(sippPk)

        val sippIsf = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_isf"
            title = "Enable SIPP: ISF"
            summary = "Uses Instant ISF (exp-weighted TDD). Turning ON will turn Dynamic Sensitivity OFF."
            isChecked = SippPrefs.enableIsf()
            isEnabled = SippPrefs.enablePk() && !preferences.get(BooleanKey.ApsUseDynamicSensitivity)
            setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                if (on && preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
                    preferences.put(BooleanKey.ApsUseDynamicSensitivity, false)
                }
                SippPrefs.setEnableIsf(on)
                safeUiRefresh()
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
                safeUiRefresh()
                true
            }
        }
        sippCategory.addPreference(sippDiaExpert)

        val sippBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_basal"
            title = "Apply SIPP Instant Basal"
            summary = "Use SIPP’s calculated basal (from Used ISF) as the current basal input."
            isChecked = SippPrefs.enableBasal()
            isEnabled = true
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableBasal(newValue as Boolean)
                safeUiRefresh()
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
                safeUiRefresh()
                true
            }
        }
        sippCategory.addPreference(sippMaxBasalToggle)

        // ===== Sleep & recovery (auto + manual window) =====
        val sleepCat = PreferenceCategory(context).apply {
            key = "sipp_sleep_recovery"
            title = "SIPP – Sleep & recovery"
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(sleepCat)

        val sleepMaster = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_sleep_recovery"
            title = "Sleep-aware DIA ceiling"
            summary = "Uses rest detection (no HR/steps) or your manual window; never shortens DIA."
            isChecked = SippPrefs.enableSleepRecovery()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setEnableSleepRecovery(newValue as Boolean)
                updateSleepSummaries()
                safeUiRefresh()
                true
            }
        }
        sleepCat.addPreference(sleepMaster)

        val sleepManual = SwitchPreferenceCompat(context).apply {
            key = "sipp_manual_sleep_enabled"
            title = "Use manual sleep window"
            summary = "Enable manual time window for sleep if you don’t wear a watch."
            isChecked = SippPrefs.manualSleepEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                SippPrefs.setManualSleepEnabled(newValue as Boolean)
                updateSleepSummaries()
                safeUiRefresh()
                true
            }
        }
        sleepCat.addPreference(sleepManual)

        val startPref = EditTextPreference(context).apply {
            key = "sipp_manual_sleep_start"
            title = "Manual sleep start (HH:MM)"
            dialogTitle = "Enter start time (HH:MM, 24h)"
            text = formatHm(SippPrefs.manualSleepStartMin())
            summary = "Current: ${formatHm(SippPrefs.manualSleepStartMin())}"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            setOnPreferenceChangeListener { _, newValue ->
                val parsed = parseHm(newValue as String?) ?: return@setOnPreferenceChangeListener false
                SippPrefs.setManualSleepStartMin(parsed)
                summary = "Current: ${formatHm(parsed)}"
                true
            }
        }
        sleepCat.addPreference(startPref)
        sleepManualStartRef = startPref

        val endPref = EditTextPreference(context).apply {
            key = "sipp_manual_sleep_end"
            title = "Manual sleep end (HH:MM)"
            dialogTitle = "Enter end time (HH:MM, 24h)"
            text = formatHm(SippPrefs.manualSleepEndMin())
            summary = "Current: ${formatHm(SippPrefs.manualSleepEndMin())}"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            setOnPreferenceChangeListener { _, newValue ->
                val parsed = parseHm(newValue as String?) ?: return@setOnPreferenceChangeListener false
                SippPrefs.setManualSleepEndMin(parsed)
                summary = "Current: ${formatHm(parsed)}"
                true
            }
        }
        sleepCat.addPreference(endPref)
        sleepManualEndRef = endPref

        // ===== SIPP readouts =====
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

        // ===== Activity fusion =====
        addSippActivityPrefs(parent, context)
        updateSleepSummaries()
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
            val flags = mutableListOf<String>()
            if (snap.hrActive) flags += "HR"
            if (snap.cadenceActive) flags += "Steps"
            if (snap.hintActive) flags += "Hint"
            val actFlags = if (flags.isEmpty()) "—" else flags.joinToString("+")
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

    // ---------- Sleep UI helpers ----------
    private fun updateSleepSummaries() {
        sleepManualStartRef?.summary = "Current: ${formatHm(SippPrefs.manualSleepStartMin())}"
        sleepManualEndRef?.summary = "Current: ${formatHm(SippPrefs.manualSleepEndMin())}"
    }

    private fun parseHm(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun formatHm(minSinceMidnight: Int): String {
        val h = (minSinceMidnight / 60) % 24
        val m = minSinceMidnight % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, m)
    }
}

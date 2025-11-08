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
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Oref Free-Peak insulin with SIPP PK handoff (DIA/Peak),
 * SIPP Instant-ISF readout (RAW exp-TDD), and SIPP Basal / Max Basal readouts.
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
        private const val RAMP_START_DIA_H = 6f   // start allowing peaks > 120 min
        private const val RAMP_END_DIA_H = 12f   // reach the global max by 12h
        private const val CAP_AT_6H_MIN = 120    // cap at 6h
    }

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_FREE_PEAK
    override val friendlyName get(): String = rh.gs(R.string.free_peak_oref)

    override fun configuration(): JSONObject =
        JSONObject()
            .put(IntKey.InsulinOrefPeak, preferences)
            .put("SIPP_CONFIG", SippPrefs.packToJson()) // export SIPP block

    override fun applyConfiguration(configuration: JSONObject) {
        configuration.store(IntKey.InsulinOrefPeak, preferences)
        SippPrefs.applyFromJson(configuration.optJSONObject("SIPP_CONFIG")) // import SIPP block
    }

    override fun commentStandardText(): String =
        rh.gs(R.string.insulin_peak_time) + ": " + peak

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

            // Current DIA in hours (same bounds we show to users)
            val curDiaH = ((persisted?.diaH ?: sipp.current().diaH))
                .coerceIn(4.5f, if (SippPrefs.allowDiaAbove9h()) 24f else 12f)

            // Proposed peak from source (persisted → live → pref)
            val fromSippMin = (persisted?.tPeakMin
                ?: (sipp.current().peakH?.times(60f)?.roundToInt() ?: profMin))

            // Saturating logical maximum for peak given current DIA
            val logicalMax = peakLogicalMax(curDiaH, SippPrefs.allowDiaAbove9h())

            fromSippMin.coerceIn(GLOBAL_PEAK_MIN_MIN, logicalMax)
        } else {
            preferences.get(IntKey.InsulinOrefPeak)
        }

    /** Monotone, saturating cap for peak time as DIA grows. */
    private fun peakLogicalMax(diaH: Float, allowExtended: Boolean): Int {
        val globalMax = if (allowExtended) GLOBAL_PEAK_MAX_EXTENDED else GLOBAL_PEAK_MAX_DEFAULT
        val d = diaH

        // Below ~6h: keep conservative cap (120 min)
        if (d <= RAMP_START_DIA_H) return CAP_AT_6H_MIN.coerceAtMost(globalMax)

        // Between 6h and 12h: linearly ramp 120 → globalMax
        if (d < RAMP_END_DIA_H) {
            val t = (d - RAMP_START_DIA_H) / (RAMP_END_DIA_H - RAMP_START_DIA_H) // 0..1
            val cap = (CAP_AT_6H_MIN + t * (globalMax - CAP_AT_6H_MIN)).roundToInt()
            return cap.coerceIn(GLOBAL_PEAK_MIN_MIN, globalMax)
        }

        // ≥12h: freeze at global max (no more growth even if DIA is 20–24h)
        return globalMax
    }

    // --------- helper: live SIPP readouts ----------
    private fun updateSippReadouts(
        sippDiaRow: Preference,
        sippPeakRow: Preference,
        sippIsfRow: Preference,
        sippBasalRow: Preference,
        sippMaxBasalRow: Preference,
        sippDiagRow: Preference
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

        // DIA readout
        if (sippPkOn) {
            val diaUpper = if (SippPrefs.allowDiaAbove9h()) 24f else 12f
            val curDiaH = (persisted?.diaH ?: sipp.current().diaH).coerceIn(4.5f, diaUpper)
            sippDiaRow.summary = String.format(
                Locale.getDefault(),
                "Instant (SIPP): %.2f h   |   Profile: %.2f h",
                curDiaH,
                profDiaH
            )
        } else {
            sippDiaRow.summary = String.format(
                Locale.getDefault(),
                "Instant (SIPP): —   |   Profile: %.2f h",
                profDiaH
            )
        }

        // Peak readout with new saturating cap
        if (sippPkOn) {
            val fromSippMin = (persisted?.tPeakMin
                ?: (sipp.current().peakH?.times(60f)?.roundToInt() ?: profPeakMin))
            val curDiaForCap = (persisted?.diaH ?: sipp.current().diaH)
                .coerceIn(4.5f, if (SippPrefs.allowDiaAbove9h()) 24f else 12f)
            val logicalMax = peakLogicalMax(curDiaForCap, SippPrefs.allowDiaAbove9h())
            val curPeakMin = fromSippMin.coerceIn(GLOBAL_PEAK_MIN_MIN, logicalMax)
            sippPeakRow.summary =
                "Instant (SIPP): $curPeakMin min   |   Profile: $profPeakMin min"
        } else {
            sippPeakRow.summary = "Instant (SIPP): —   |   Profile: $profPeakMin min"
        }

        // ISF readout = RAW instant SIPP ISF + Profile side-by-side + low-BG context hint
        val rawIsfMgdl = SippPrefs.lastRawInstantIsfMgdl()
        val (ctxBgMgdl, ctxLowMgdl) = SippPrefs.lastIsfContext()
        val profileIsfDisplay = profIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val profileIsfUnit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"

        val instantDisplay = rawIsfMgdl?.let { if (unitsMmol) it / 18.0 else it }
        val unit = if (unitsMmol) "mmol/L/U" else "mg/dL/U"

        sippIsfRow.summary = when {
            instantDisplay != null && instantDisplay > 0.0 -> {
                val left = String.format(
                    Locale.getDefault(),
                    "Instant (SIPP): %.2f %s",
                    instantDisplay,
                    unit
                )
                val right = if (profileIsfDisplay != null)
                    String.format(
                        Locale.getDefault(),
                        " | Profile: %.2f %s",
                        profileIsfDisplay,
                        profileIsfUnit
                    )
                else " | Profile: —"
                val lowHint =
                    if (ctxBgMgdl != null && ctxLowMgdl != null && ctxBgMgdl < ctxLowMgdl)
                        "  • BG below target at last save: safety rails apply"
                    else
                        ""
                left + right + lowHint
            }

            else -> {
                if (profileIsfDisplay != null)
                    String.format(
                        Locale.getDefault(),
                        "Instant (SIPP): — | Profile: %.2f %s",
                        profileIsfDisplay,
                        profileIsfUnit
                    )
                else
                    "Instant (SIPP): — | Profile: —"
            }
        }

        // Basal readouts (U/h) — Instant and Max
        val instBasal = if (SippPrefs.enableBasal()) SippPrefs.lastInstantBasalUph() else null
        sippBasalRow.summary = when {
            instBasal != null && instBasal > 0.0 ->
                String.format(
                    Locale.getDefault(),
                    "Instant (SIPP): %.2f U/h   |   Profile: %.2f U/h",
                    instBasal,
                    profBasalUph
                )
            else ->
                String.format(
                    Locale.getDefault(),
                    "Instant (SIPP): —   |   Profile: %.2f U/h",
                    profBasalUph
                )
        }

        val instMaxBasal =
            if (SippPrefs.enableMaxBasal()) SippPrefs.lastMaxBasalUph() else null
        sippMaxBasalRow.summary = when {
            instMaxBasal != null && instMaxBasal > 0.0 ->
                String.format(
                    Locale.getDefault(),
                    "Instant (SIPP): %.2f U/h   |   Pref: %.2f U/h",
                    instMaxBasal,
                    prefMaxBasalUph
                )
            else ->
                String.format(
                    Locale.getDefault(),
                    "Instant (SIPP): —   |   Pref: %.2f U/h",
                    prefMaxBasalUph
                )
        }

        // Diagnostics (Confidence | RMSE | Bias)
        runCatching {
            val d = sipp.diagnostics()
            val rmseStr = String.format(Locale.getDefault(), "%.0f", d.rmse)
            val biasStr = String.format(Locale.getDefault(), "%+.0f", d.bias)
            val confStr = String.format(Locale.getDefault(), "%.2f", d.confidence)
            sippDiagRow.summary = "Confidence: $confStr  |  RMSE: $rmseStr  |  Bias: $biasStr"
        }.onFailure {
            sippDiagRow.summary = "Confidence: —  |  RMSE: —  |  Bias: —"
        }
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

        // Initialize plugin-local prefs store
        SippPrefs.init(context)

        // ===== Free-Peak section =====
        val fpCat = PreferenceCategory(context).apply {
            key = "insulin_free_peak_settings"
            title = rh.gs(R.string.insulin_oref_peak)
            initialExpandedChildrenCount = Int.MAX_VALUE
        }
        parent.addPreference(fpCat)
        fpCat.addPreference(
            app.aaps.core.validators.preferences.AdaptiveIntPreference(
                ctx = context,
                intKey = IntKey.InsulinOrefPeak,
                title = R.string.insulin_peak_time
            )
        )

        // ===== SIPP section =====
        val sippCategory = PreferenceCategory(context).also {
            it.key = "insulin_sipp_settings"
            it.title = "SIPP (Sentinel Instant PK/PD)"
            it.initialExpandedChildrenCount = Int.MAX_VALUE
        }
        parent.addPreference(sippCategory)

        // Insulin archetype selector for SIPP seeding
        val insulinTypePref = ListPreference(context).apply {
            key = "sipp_insulin_archetype"
            title = "Insulin archetype for SIPP seeding"
            entries = arrayOf(
                "AUTO (use current preset)",
                "Rapid-acting (Humalog / NovoRapid)",
                "Ultra-rapid (Fiasp)",
                "Lyumjev"
            )
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

        // Master PK (Peak & DIA)
        val sippPk = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_pk"
            title = "Enable SIPP: PK (DIA & Peak)"
            summary = "Let SIPP auto-tune DIA/Peak with safety rails."
            isChecked = SippPrefs.enablePk()
        }
        sippCategory.addPreference(sippPk)

        // ISF toggle (mutually exclusive with DS)
        val sippIsf = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_isf"
            title = "Enable SIPP: ISF"
            summary =
                "Uses Instant ISF (exp-weighted TDD). Turning ON will turn Dynamic Sensitivity OFF."
            isChecked = SippPrefs.enableIsf()
            isEnabled = SippPrefs.enablePk() && !preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        }
        sippCategory.addPreference(sippIsf)

        // Expert: allow DIA > 9 h (up to 24 h)
        val sippDiaExpert = SwitchPreferenceCompat(context).apply {
            key = "sipp_allow_dia_above_9h"
            title = "Allow DIA > 9 h (expert)"
            summary = "Allows SIPP to extend DIA up to 24 h for slow sites/stacking."
            isChecked = SippPrefs.allowDiaAbove9h()
            isEnabled = SippPrefs.enablePk()
        }
        sippCategory.addPreference(sippDiaExpert)

        // NEW: Apply SIPP Instant Basal
        val sippBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_basal"
            title = "Apply SIPP Instant Basal"
            summary = "Use SIPP’s calculated basal (from Instant ISF) as the current basal input."
            isChecked = SippPrefs.enableBasal()
            isEnabled = true
        }
        sippCategory.addPreference(sippBasalToggle)

        // NEW: Apply SIPP Max Temp Basal cap
        val sippMaxBasalToggle = SwitchPreferenceCompat(context).apply {
            key = "sipp_enable_max_basal"
            title = "Apply SIPP Max Temp Basal cap"
            summary = "Limit temp basals to SIPP’s suggested maximum (Max U/h a Temp Basal can be set to)."
            isChecked = SippPrefs.enableMaxBasal()
            isEnabled = true
        }
        sippCategory.addPreference(sippMaxBasalToggle)

        // Optional site inputs
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
            summary =
                "OFF recommended for Medtrum Nano. Enable only if you know age affects absorption."
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

        // Readouts (non-clickable)
        val sippDiaRow =
            Preference(context).apply { key = "sipp_readout_dia"; title = "DIA"; isSelectable = false }
        val sippPeakRow = Preference(context).apply {
            key = "sipp_readout_peak"; title = "Peak Time"; isSelectable = false
        }
        val sippIsfRow =
            Preference(context).apply { key = "sipp_readout_isf"; title = "ISF"; isSelectable = false }
        val sippBasalRow = Preference(context).apply {
            key = "sipp_readout_basal"; title = "Basal (U/h)"; isSelectable = false
        }
        val sippMaxBasalRow = Preference(context).apply {
            key = "sipp_readout_max_basal"; title = "Max Basal (U/h)"; isSelectable = false
        }
        val sippDiagRow = Preference(context).apply {
            key = "SIPP_readout_diag"; title = "Verification"; isSelectable = false
        }
        val sippRefresh = Preference(context).apply {
            key = "SIPP_refresh"
            title = "Refresh SIPP readouts"
            summary = "Recalculate on the next BG tick and update the panel"
        }

        sippCategory.addPreference(sippDiaRow)
        sippCategory.addPreference(sippPeakRow)
        sippCategory.addPreference(sippIsfRow)
        sippCategory.addPreference(sippBasalRow)
        sippCategory.addPreference(sippMaxBasalRow)
        sippCategory.addPreference(sippDiagRow)
        sippCategory.addPreference(sippRefresh)

        // Listeners
        insulinTypePref.setOnPreferenceChangeListener { pref, newValue ->
            val v = newValue as String
            SippPrefs.setInsulinArchetype(v)
            pref.summary = when (v) {
                "RAPID"   -> "Current: Rapid-acting (Humalog / NovoRapid)"
                "FIASP"   -> "Current: Ultra-rapid (Fiasp)"
                "LYUMJEV" -> "Current: Lyumjev"
                else      -> "Current: AUTO (use current preset)"
            }
            true
        }

        sippPk.setOnPreferenceChangeListener { _, newValue ->
            SippPrefs.setEnablePk(newValue as Boolean)
            sippIsf.isEnabled = SippPrefs.enablePk() && !preferences.get(BooleanKey.ApsUseDynamicSensitivity)
            sippDiaExpert.isEnabled = SippPrefs.enablePk()
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }
        sippIsf.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            if (on && preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
                // Mutual exclusion: SIPP ISF ON forces DS OFF
                preferences.put(BooleanKey.ApsUseDynamicSensitivity, false)
            }
            SippPrefs.setEnableIsf(on)
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }
        sippDiaExpert.setOnPreferenceChangeListener { _, newValue ->
            SippPrefs.setAllowDiaAbove9h(newValue as Boolean)
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }

        // NEW: listeners for SIPP Basal toggles
        sippBasalToggle.setOnPreferenceChangeListener { _, newValue ->
            SippPrefs.setEnableBasal(newValue as Boolean)
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }
        sippMaxBasalToggle.setOnPreferenceChangeListener { _, newValue ->
            SippPrefs.setEnableMaxBasal(newValue as Boolean)
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }

        siteLocationPref.setOnPreferenceChangeListener { pref, newValue ->
            val v = newValue as String
            SippPrefs.setSiteLocation(v)
            pref.summary = "Current: $v"
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }
        siteAgeEnabledPref.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            SippPrefs.setSiteAgeEnabled(on)
            siteAgePref.isEnabled = on
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }
        siteAgePref.setOnPreferenceChangeListener { pref, newValue ->
            val v = (newValue as String).ifBlank { "1" }
            SippPrefs.setSiteAgeH(v)
            pref.summary = "Current: $v"
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }

        // Manual refresh triggers same pipeline as a BG tick, then refresh UI
        sippRefresh.setOnPreferenceClickListener {
            rxBus.send(EventNewBG(System.currentTimeMillis()))
            updateSippReadouts(
                sippDiaRow,
                sippPeakRow,
                sippIsfRow,
                sippBasalRow,
                sippMaxBasalRow,
                sippDiagRow
            )
            true
        }

        // Initial fill (will use persisted state if present)
        updateSippReadouts(
            sippDiaRow,
            sippPeakRow,
            sippIsfRow,
            sippBasalRow,
            sippMaxBasalRow,
            sippDiagRow
        )
    }
}

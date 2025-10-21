package app.aaps.plugins.insulin

import android.content.Context
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.plugins.insulin.sipp.SentinelPkPdController
import app.aaps.plugins.insulin.sipp.SippPrefs
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Created by adrian on 14/08/17.
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
    rHelp,
    wrapProfileFunc(profFunc, hardLimits), // clamp profile DIA globally for this plugin
    rxBus,
    aapsLogger,
    config,
    hardLimits,
    uiInteraction
) {

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_FREE_PEAK
    override val friendlyName get(): String = rh.gs(R.string.free_peak_oref)

    override fun configuration(): JSONObject =
        JSONObject().put(IntKey.InsulinOrefPeak, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration.store(IntKey.InsulinOrefPeak, preferences)
    }

    override fun commentStandardText(): String =
        rh.gs(R.string.insulin_peak_time) + ": " + peak

    // DIA for dosing (no HardLimits.kt edits needed)
    override val userDefinedDia: Double
        get() {
            val profileDiaRaw = profileFunction.getProfile()?.dia ?: hardLimits.minDia()
            val diaUiMax = if (SippPrefs.allowDiaAbove9h()) 20.0 else 12.0
            val profDiaH = profileDiaRaw.coerceIn(hardLimits.minDia(), diaUiMax)
            if (!SippPrefs.enablePk()) return profDiaH
            val est = sipp.current()
            return est.diaH.toDouble().coerceIn(hardLimits.minDia(), diaUiMax)
        }

    // Peak (minutes) from SIPP or profile if SIPP PK is off
    override val peak: Int
        get() = if (SippPrefs.enablePk()) {
            val prefMin = preferences.get(IntKey.InsulinOrefPeak)
            val peakH = (sipp.current().peakH ?: (prefMin / 60f)).coerceIn(0.6f, 4.0f)
            (peakH * 60f).roundToInt()
        } else preferences.get(IntKey.InsulinOrefPeak)

    // Readouts
    private fun updateSippReadouts(
        sippDiaRow: Preference,
        sippPeakRow: Preference,
        sippIsfRow: Preference
    ) {
        val profileDiaRaw = profileFunction.getProfile()?.dia ?: hardLimits.minDia()
        val diaUiMax = if (SippPrefs.allowDiaAbove9h()) 20.0 else 12.0
        val profDiaH = profileDiaRaw.coerceIn(hardLimits.minDia(), diaUiMax)
        val profPeakMin = preferences.get(IntKey.InsulinOrefPeak)

        val sippPkOn = SippPrefs.enablePk()
        val sippIsfOn = SippPrefs.enableIsf()
        val dsOn = preferences.get(BooleanKey.ApsUseDynamicSensitivity)

        if (sippPkOn) {
            val e = sipp.current()
            val diaUpper = if (SippPrefs.allowDiaAbove9h()) 20f else 12f
            val curDiaH = e.diaH.coerceIn(hardLimits.minDia().toFloat(), diaUpper)
            sippDiaRow.summary = String.format(
                Locale.getDefault(),
                "Current (SIPP): %.2f h   |   Profile: %.2f h", curDiaH, profDiaH
            )
        } else {
            sippDiaRow.summary = String.format(
                Locale.getDefault(),
                "Current (SIPP): —   |   Profile: %.2f h", profDiaH
            )
        }

        if (sippPkOn) {
            val e = sipp.current()
            val curPeakH = (e.peakH ?: (profPeakMin / 60f)).coerceIn(0.6f, 4.0f)
            sippPeakRow.summary = "Current (SIPP): ${(curPeakH * 60f).roundToInt()} min   |   Profile: $profPeakMin min"
        } else {
            sippPeakRow.summary = "Current (SIPP): —   |   Profile: $profPeakMin min"
        }

        if (sippIsfOn && !dsOn) {
            val scalePct = (sipp.current().isfScale * 100f).roundToInt()
            sippIsfRow.summary = "Current (SIPP): $scalePct% of base"
        } else {
            sippIsfRow.summary = "Current (SIPP): —"
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
        SippPrefs.init(context)

        // Free-Peak section
        val fpCat = PreferenceCategory(context).apply {
            key = "insulin_free_peak_settings"
            title = rh.gs(R.string.insulin_oref_peak)
            initialExpandedChildrenCount = 0
        }
        parent.addPreference(fpCat)
        fpCat.addPreference(
            AdaptiveIntPreference(ctx = context, intKey = IntKey.InsulinOrefPeak, title = R.string.insulin_peak_time)
        )

        // SIPP section
        val sippCategory = PreferenceCategory(context).also {
            it.key = "insulin_SIPP_settings"
            it.title = "SIPP (Auto PK/PD)"
            it.initialExpandedChildrenCount = 0
        }
        parent.addPreference(sippCategory)

        // Master PK (Peak & DIA)
        val sippPk = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_pk"
            title = "Enable SIPP: PK (DIA & Peak)"
            summary = "Let SIPP auto-tune DIA/Peak with safety rails."
            isChecked = SippPrefs.enablePk()
        }
        sippCategory.addPreference(sippPk)

        // ISF (mutually exclusive with Dynamic Sensitivity)
        val sippIsf = SwitchPreferenceCompat(context).apply {
            key = "SIPP_enable_isf"
            title = "Enable SIPP: ISF"
            summary = "Guarded ISF scaling (disabled when Dynamic Sensitivity is on)."
            isChecked = SippPrefs.enableIsf()
            isEnabled = SippPrefs.enablePk() && !preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        }
        sippCategory.addPreference(sippIsf)

        // Expert: allow DIA > 9 h
        val sippDiaExpert = SwitchPreferenceCompat(context).apply {
            key = "SIPP_allow_dia_above_9h"
            title = "Allow DIA > 9 h (expert)"
            summary = "Lets SIPP extend DIA to handle slow sites/stacking (up to 20 h)."
            isChecked = SippPrefs.allowDiaAbove9h()
            isEnabled = SippPrefs.enablePk()
        }
        sippCategory.addPreference(sippDiaExpert)

        // Optional site inputs
        val siteLocationPref = ListPreference(context).apply {
            key = "SIPP_site_location"
            title = "Site Location"
            entries = arrayOf("Abdomen", "Arm", "Thigh")
            entryValues = arrayOf("ABDOMEN", "ARM", "THIGH")
            val currentLoc = SippPrefs.siteLocation()
            value = currentLoc
            summary = "Current: $currentLoc"
        }
        sippCategory.addPreference(siteLocationPref)

        val siteAgeEnabledPref = SwitchPreferenceCompat(context).apply {
            key = "SIPP_site_age_enabled"
            title = "Use Site Age Effect (advanced)"
            summary = "OFF recommended for Medtrum Nano. Enable only if you know age affects absorption."
            isChecked = SippPrefs.siteAgeEnabled()
        }
        sippCategory.addPreference(siteAgeEnabledPref)

        val siteAgePref = EditTextPreference(context).apply {
            key = "SIPP_site_age_h"
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
        val sippDiaRow = Preference(context).apply {
            key = "SIPP_readout_dia"
            title = "DIA"
            isSelectable = false
        }
        sippCategory.addPreference(sippDiaRow)

        val sippPeakRow = Preference(context).apply {
            key = "SIPP_readout_peak"
            title = "Peak Time"
            isSelectable = false
        }
        sippCategory.addPreference(sippPeakRow)

        val sippIsfRow = Preference(context).apply {
            key = "SIPP_readout_isf"
            title = "ISF"
            isSelectable = false
        }
        sippCategory.addPreference(sippIsfRow)

        // Listeners
        sippPk.setOnPreferenceChangeListener { _, newValue ->
            SippPrefs.setEnablePk(newValue as Boolean)
            sippIsf.isEnabled = SippPrefs.enablePk() && !preferences.get(BooleanKey.ApsUseDynamicSensitivity)
            sippDiaExpert.isEnabled = SippPrefs.enablePk()
            updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
            true
        }
        sippIsf.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            if (on && preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
                preferences.put(BooleanKey.ApsUseDynamicSensitivity, false)
            }
            SippPrefs.setEnableIsf(on)
            updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
            true
        }
        sippDiaExpert.setOnPreferenceChangeListener { _, newValue ->
            SippPrefs.setAllowDiaAbove9h(newValue as Boolean)
            updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
            true
        }
        siteLocationPref.setOnPreferenceChangeListener { pref, newValue ->
            val v = newValue as String
            SippPrefs.setSiteLocation(v)
            pref.summary = "Current: $v"
            updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
            true
        }
        siteAgeEnabledPref.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            SippPrefs.setSiteAgeEnabled(on)
            siteAgePref.isEnabled = on
            updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
            true
        }
        siteAgePref.setOnPreferenceChangeListener { pref, newValue ->
            val v = (newValue as String).ifBlank { "1" }
            SippPrefs.setSiteAgeH(v)
            pref.summary = "Current: $v"
            updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
            true
        }

        // Initial fill
        updateSippReadouts(sippDiaRow, sippPeakRow, sippIsfRow)
    }
}

/** Clamp profile.dia for all consumers inside this plugin. */
private fun wrapProfileFunc(
    base: ProfileFunction,
    hardLimits: HardLimits
): ProfileFunction = object : ProfileFunction by base {
    override fun getProfile(): Profile? {
        val p = base.getProfile() ?: return null
        val diaUiMax = if (SippPrefs.allowDiaAbove9h()) 20.0 else 12.0
        return object : Profile by p {
            override val dia: Double
                get() = p.dia.coerceIn(hardLimits.minDia(), diaUiMax)
        }
    }
}

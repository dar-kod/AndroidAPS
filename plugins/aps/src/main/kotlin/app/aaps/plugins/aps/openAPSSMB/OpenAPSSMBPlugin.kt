package app.aaps.plugins.aps.openAPSSMB

import android.content.Context
import android.content.Intent
import android.util.LongSparseArray
import androidx.core.net.toUri
import androidx.core.util.forEach
import androidx.core.util.size
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.insulin.sipp.SentinelPkPdController
import app.aaps.plugins.insulin.sipp.SippPrefs
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@Singleton
open class OpenAPSSMBPlugin @Inject constructor(
    loggerParam: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalSMB: DetermineBasalSMB,
    private val profiler: Profiler,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val apsResultProvider: Provider<APSResult>,
    private val sentinelController: SentinelPkPdController
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openapssmb)
        .shortName(app.aaps.core.ui.R.string.smb_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList(showInList = { config.APS })
        .description(R.string.description_smb)
        .setDefault(),
    loggerParam, rh
), APS, PluginConstraints {

    // ===== Adaptive + safety defaults =====
    private val bolusLookbackPerDiaFrac = 0.04
    private val bolusLookbackMin = 45
    private val bolusLookbackMax = 90

    private val bolusSumPer30MinPerTdd = 0.03
    private val bolusSumPer30MinMin = 0.6
    private val bolusSumPer30MinMax = 1.5

    private val basalExcessRatio = 1.20
    private val suspendRecentMin = 15

    // Wear subscriptions & dedupe for steps
    private val wearDisposables = CompositeDisposable()
    @Volatile private var lastStepIngestMin: Long = -1

    override fun onStart() {
        super.onStart()
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
            if (variableSens > 0) dynIsfCache.put(key, variableSens)
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")

        // Wire HR + Steps from Wear into SIPP
        runCatching {
            val hrSub = rxBus
                .toObservable(EventData.ActionHeartRate::class.java)
                .subscribe({ hrEvt ->
                               val bpm = hrEvt.beatsPerMinute.roundToInt().coerceIn(30, 220)
                               sentinelController.setHr(bpm)
                               aapsLogger.debug(LTag.APS, "SIPP HR wired: $bpm bpm @${hrEvt.timestamp}")
                           }, { e -> aapsLogger.error(LTag.APS, "HR→SIPP subscription error", e) })
            wearDisposables.add(hrSub)

            val stepsSub = rxBus
                .toObservable(EventData.ActionStepsRate::class.java)
                .subscribe({ stEvt ->
                               val perMin = when {
                                   stEvt.steps5min != 0 -> (stEvt.steps5min / 5).coerceAtLeast(0)
                                   stEvt.steps10min != 0 -> (stEvt.steps10min / 10).coerceAtLeast(0)
                                   stEvt.steps15min != 0 -> (stEvt.steps15min / 15).coerceAtLeast(0)
                                   else                 -> 0
                               }
                               if (perMin > 0) {
                                   val minuteTs = stEvt.timestamp / 60_000L
                                   if (minuteTs != lastStepIngestMin) {
                                       sentinelController.onStep(perMin, stEvt.timestamp)
                                       lastStepIngestMin = minuteTs
                                       aapsLogger.debug(LTag.APS, "SIPP STEPS wired: ~${perMin} spm @${stEvt.timestamp}")
                                   }
                               }
                           }, { e -> aapsLogger.error(LTag.APS, "STEPS→SIPP subscription error", e) })
            wearDisposables.add(stepsSub)
        }.onFailure { aapsLogger.error(LTag.APS, "Wear wiring start failed", it) }
    }

    override fun onStop() {
        super.onStop()
        wearDisposables.clear()
    }

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null
    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)

    private val dynIsfCache = LongSparseArray<Double>()

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as ProfileSealed.EPS).value.originalPercentage / 100.0
        val sensitivity = calculateVariableIsf(start, multiplier)
        if (sensitivity.second == null)
            uiInteraction.addNotificationValidTo(
                Notification.DYN_ISF_FALLBACK, start,
                rh.gs(R.string.fallback_to_isf_no_tdd, sensitivity.first), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
        else uiInteraction.dismissNotification(Notification.DYN_ISF_FALLBACK)
        profiler.log(
            LTag.APS,
            "getIsfMgdl() multiplier=$multiplier reason=${sensitivity.first} sensitivity=${sensitivity.second} caller=$caller",
            start
        )
        return sensitivity.second
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        dynIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean = try {
        activePlugin.activePump.pumpDescription.isTempBasalCapable
    } catch (_: Exception) {
        true
    }

    override fun specialShowInListCondition(): Boolean = try {
        activePlugin.activePump.pumpDescription.isTempBasalCapable
    } catch (_: Exception) {
        true
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported()
        val autoSensOrDynIsfSensEnabled = if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)
        } else preferences.get(BooleanKey.ApsUseAutosens)

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible =
            smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible =
            smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible =
            smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible =
            smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible =
            autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible =
            autoSensOrDynIsfSensEnabled

        // DS ON forces SIPP-ISF OFF
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseDynamicSensitivity.key)
            ?.setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                if (on && SippPrefs.enableIsf()) SippPrefs.setEnableIsf(false)
                true
            }

        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible =
            smbEnabled && uamEnabled
    }

    @Synchronized
    private fun calculateVariableIsf(timestamp: Long, multiplier: Double): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return Pair("OFF", null)

        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null && result.variableSens != 0.0) {
            return Pair("DB", result.variableSens)
        }

        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        val cached = dynIsfCache[key]
        if (cached != null && timestamp < dateUtil.now()) return Pair("HIT", cached)

        val dynIsfResult = calculateRawDynIsf(multiplier)
        if (!dynIsfResult.tddPartsCalculated()) return Pair("TDD miss", null)

        dynIsfCache.put(key, dynIsfResult.variableSensitivity)
        if (dynIsfCache.size > 1000) dynIsfCache.clear()
        return Pair("CALC", dynIsfResult.variableSensitivity)
    }

    internal class DynIsfResult {
        var tdd1D: Double? = null
        var tdd7D: Double? = null
        var tddLast24H: Double? = null
        var tddLast4H: Double? = null
        var tddLast8to4H: Double? = null
        var tdd: Double? = null
        var variableSensitivity: Double? = null
        var insulinDivisor: Int = 0
        var tddLast24HCarbs = 0.0
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false
        fun tddPartsCalculated() =
            tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null
    }

    /** Pure instant-ISF math (exp-like weights via coarse buckets) with EPS multiplier. */
    private fun calculateRawDynIsf(multiplier: Double): DynIsfResult {
        val dyn = DynIsfResult()

        dyn.tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount
        tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.let {
            dyn.tdd7D = it.data.totalAmount
            dyn.tdd7DDataCarbs = it.data.carbs
            dyn.tdd7DAllDaysHaveCarbs = it.allDaysHaveCarbs
        }

        tddCalculator.calculateDaily(-24L, 0L)?.also {
            dyn.tddLast24H = it.totalAmount
            dyn.tddLast24HCarbs = it.carbs
        }

        fun safeAmt(fromHour: Long, toHour: Long): Double =
            tddCalculator.calculateDaily(fromHour, toHour)?.totalAmount ?: 0.0

        val amt0to4 = safeAmt(-4L, 0L)
        val amt4to12 = safeAmt(-12L, -4L)
        val amt12to24 = safeAmt(-24L, -12L)

        val uPerH0to4 = amt0to4 / 4.0
        val uPerH4to12 = amt4to12 / 8.0
        val uPerH12to24 = amt12to24 / 12.0

        val w0to4 = 0.60
        val w4to12 = 0.30
        val w12to24 = 0.10

        val weightedUph = (w0to4 * uPerH0to4) + (w4to12 * uPerH4to12) + (w12to24 * uPerH12to24)
        val instantTdd = (weightedUph * 24.0).coerceAtLeast(0.0)

        val effectiveTdd = (instantTdd * multiplier).let { if (it <= 0.0) 0.1 else it }

        dyn.tdd = effectiveTdd
        dyn.variableSensitivity = Round.roundTo(1800.0 / effectiveTdd, 0.1) // mg/dL per U
        dyn.tddLast4H = amt0to4
        dyn.tddLast8to4H = amt4to12
        return dyn
    }

    /** Signals that ARW-MB uses to adapt Max Basal. */
    private data class ArwSignals(
        val bgNow: Double,
        val deltaPerMin: Double,
        val iobU: Double,
        val minBg: Double,
        val deliveredBasalNow: Double,
        val recentSuspendMin: Int,
        val flatBGs: Boolean,
        val isTempTarget: Boolean
    )

    /** Compute & persist SIPP Instant Basal and ARW-MB Max Basal. */
    private fun persistInstantAndMaxBasal(profile: Profile, sensMgdl: Double, sig: ArwSignals) {
        if (sensMgdl <= 0.0) return
        val tddEst = 1800.0 / sensMgdl
        val instantBasal = Round.roundTo(0.45 * tddEst / 24.0, 0.01)

        // ARW-MB risk mapping
        val tddPerH = (tddEst / 24.0).coerceAtLeast(0.05)
        val trendRisk = (abs(sig.deltaPerMin) / 2.0).coerceIn(0.0, 1.0)
        val iobRisk = (sig.iobU / (2.0 * tddPerH)).coerceIn(0.0, 1.0)
        val lowRisk = if (sig.bgNow < sig.minBg) 1.0 else 0.0
        val suspendRisk = if (sig.recentSuspendMin in 1..suspendRecentMin) 1.0 else 0.0
        val flatBonus = if (sig.flatBGs) -0.15 else 0.0
        val ttBonus = if (sig.isTempTarget) 0.10 else 0.0

        val riskIndex = (0.45 * trendRisk +
            0.35 * iobRisk +
            0.15 * lowRisk +
            0.05 * suspendRisk +
            flatBonus + ttBonus).coerceIn(0.0, 1.0)

        val minMult = 2.2
        val maxMult = 3.8
        val arwMult = (maxMult - (maxMult - minMult) * riskIndex)

        val arwFromInstant = arwMult * instantBasal
        val fromScheduled = 1.8 * profile.getBasal()
        val fromDailyCap = profile.getMaxDailyBasal() * preferences.get(DoubleKey.ApsMaxDailyMultiplier)
        val hardCeil = hardLimits.maxBasal()
        val upperGuard = minOf(fromDailyCap, hardCeil)

        val trio = doubleArrayOf(arwFromInstant, fromScheduled, upperGuard).sorted()
        val physSuggested = trio[1]
        val minFloor = (1.4 * instantBasal).coerceAtMost(upperGuard)

        val suggestedMax = Round.roundTo(
            physSuggested.coerceAtLeast(minFloor).coerceAtMost(upperGuard),
            0.01
        )

        val nowTs = dateUtil.now()
        runCatching { SippPrefs.saveLastInstantBasalUph(instantBasal, nowTs) }
        runCatching { SippPrefs.saveLastMaxBasalUph(suggestedMax, nowTs) }
    }

    /** First-time synthesis if user enabled SIPP basal/max but nothing persisted yet (prevents “—”). */
    private fun synthesizeIfMissing(profile: Profile, usedIsfMgdl: Double, deliveredBasalNow: Double, minutesRunning: Int, minBg: Double, isTempTarget: Boolean) {
        if (usedIsfMgdl <= 0.0) return
        val haveBasal = (SippPrefs.lastInstantBasalUph() ?: 0.0) > 0.0
        val haveMax = (SippPrefs.lastMaxBasalUph() ?: 0.0) > 0.0
        if (haveBasal && haveMax) return
        val gs = glucoseStatusProvider.glucoseStatusData
        val dpm = try {
            gs?.delta ?: 0.0
        } catch (_: Throwable) {
            0.0
        }
        val iobU = runCatching {
            val autosensResult = AutosensResult()
            iobCobCalculator.calculateIobArrayForSMB(autosensResult, false, SMBDefaults.half_basal_exercise_target, isTempTarget)
                .lastOrNull()?.iob ?: 0.0
        }.getOrDefault(0.0)
        val sig = ArwSignals(
            bgNow = gs?.glucose ?: 0.0,
            deltaPerMin = dpm,
            iobU = iobU,
            minBg = minBg,
            deliveredBasalNow = deliveredBasalNow,
            recentSuspendMin = if (deliveredBasalNow == 0.0) minutesRunning else 0,
            flatBGs = (bgQualityCheck.state == BgQualityCheck.State.FLAT),
            isTempTarget = isTempTarget
        )
        persistInstantAndMaxBasal(profile, usedIsfMgdl, sig)
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val profileAny = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profileAny == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }

        val epsMultiplier: Double = (profileAny as? ProfileSealed.EPS)
            ?.value?.originalPercentage?.div(100.0) ?: 1.0

        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val profile = profileAny
        val inputConstraints = ConstraintObject(0.0, aapsLogger)

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()), app.aaps.core.ui.R.string.profile_carbs_ratio_value, hardLimits.minIC(), hardLimits.maxIC())) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSSMBPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        val dynIsfMode =
            preferences.get(BooleanKey.ApsUseDynamicSensitivity) && hardLimits.checkHardLimits(
                preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(),
                R.string.dyn_isf_adjust_title,
                IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(),
                IntKey.ApsDynIsfAdjustmentFactor.max.toDouble()
            )
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        val scheduledBasal: Double = profile.getBasal()

        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        var autosensResult = AutosensResult()
        val dynIsfResult = calculateRawDynIsf(epsMultiplier)

        if (dynIsfMode && !dynIsfResult.tddPartsCalculated()) {
            uiInteraction.addNotificationValidTo(
                Notification.SMB_FALLBACK, dateUtil.now(),
                rh.gs(R.string.fallback_smb_no_tdd), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
            inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, rh.gs(R.string.fallback_smb_no_tdd), this) })
        } else if (dynIsfMode && dynIsfResult.tddPartsCalculated()) {
            uiInteraction.dismissNotification(Notification.SMB_FALLBACK)
            val tddRatio =
                if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)) (dynIsfResult.tddLast24H!! / dynIsfResult.tdd7D!!.coerceAtLeast(0.1)) else 1.0
            val carbsRatio =
                if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity) && dynIsfResult.tddLast24HCarbs != 0.0 && dynIsfResult.tdd7DDataCarbs != 0.0 && dynIsfResult.tdd7DAllDaysHaveCarbs)
                    ((dynIsfResult.tddLast24HCarbs / dynIsfResult.tdd7DDataCarbs - 1.0) * 0.6) + 1.0
                else 1.0
            autosensResult = AutosensResult(ratio = tddRatio / carbsRatio, ratioFromTdd = tddRatio, ratioFromCarbs = carbsRatio)
        } else {
            if (constraintsChecker.isAutosensModeEnabled().value()) {
                val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
                if (autosensData == null) {
                    rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                    return
                }
                autosensResult = autosensData.autosensResult
            } else autosensResult.sensResult = "autosens disabled"
        }

        @Suppress("KotlinConstantConditions")
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(
            autosensResult,
            SMBDefaults.exercise_mode,
            SMBDefaults.half_basal_exercise_target,
            isTempTarget
        )
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        // SIPP-ISF over DS if SIPP-ISF ON
        if (SippPrefs.enableIsf() && preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            preferences.put(BooleanKey.ApsUseDynamicSensitivity, false)
            aapsLogger.debug(LTag.APS, "DS disabled automatically; SIPP-ISF has priority.")
            rxBus.send(EventOpenAPSUpdateGui())
        }

        // ===================== ADAPTIVE GATING (PK learning) =====================
        val reasons = mutableListOf<String>()

        val minutesSinceLastBolus: Int = runCatching {
            val m = iobCobCalculator::class.java.getMethod("getLastBolusTime")
            val lastBolusMs = (m.invoke(iobCobCalculator) as Long)
            ((now - lastBolusMs) / 60000L).toInt()
        }.getOrElse { 9_999 }

        val diaMin = (profile.dia * 60.0).toInt()
        val lookbackTarget = (bolusLookbackPerDiaFrac * diaMin).toInt()
        val bolusLookbackMinReq = lookbackTarget.coerceIn(bolusLookbackMin, bolusLookbackMax)
        if (minutesSinceLastBolus < bolusLookbackMinReq) reasons += "bolus<${bolusLookbackMinReq}min (was ${minutesSinceLastBolus}min)"

        val last1hIns = tddCalculator.calculateDaily(-1L, 0L)?.totalAmount ?: 0.0
        val tdd7d = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.data?.totalAmount ?: 0.0
        val per30uThresh = (bolusSumPer30MinPerTdd * tdd7d).coerceIn(bolusSumPer30MinMin, bolusSumPer30MinMax)
        if (last1hIns >= 2.0 * per30uThresh) reasons += "insulinLoad1h≥${"%.2f".format(2.0 * per30uThresh)}U (was ${"%.2f".format(last1hIns)}U)"

        val deliveredBasalNow = tb?.convertedToAbsolute(now, profile) ?: activePlugin.activePump.baseBasalRate
        val minutesRunning = tb?.getPassedDurationToTimeInMinutes(now) ?: 0
        if (deliveredBasalNow >= basalExcessRatio * scheduledBasal) {
            reasons += "basal≥${(basalExcessRatio * 100).toInt()}% sched (rate=${"%.2f".format(deliveredBasalNow)}U/h, sched=${"%.2f".format(scheduledBasal)}U/h)"
        }
        if (deliveredBasalNow == 0.0 && minutesRunning in 0..suspendRecentMin) {
            reasons += "recentSuspend≤${suspendRecentMin}min"
        }

        val allowPkLearning = reasons.isEmpty()
        if (!allowPkLearning) aapsLogger.debug(LTag.APS, "SIPP PK update gated: ${reasons.joinToString("; ")}")
        // ========================================================================

        if (allowPkLearning) {
            runCatching {
                val gs = glucoseStatus
                val nowMs = now
                val bgNow = gs.glucose
                val deltaPerMin = try {
                    gs.delta
                } catch (_: Throwable) {
                    0.0
                }
                val bgPred = bgNow + 15.0 * deltaPerMin

                val iobU = runCatching { iobArray.lastOrNull()?.iob ?: 0.0 }.getOrDefault(0.0)
                val basalSuspendedMin = if (deliveredBasalNow == 0.0) minutesRunning else 0
                val siteAgeHours = if (SippPrefs.siteAgeEnabled()) SippPrefs.siteAgeH().toDoubleOrNull() ?: 1.0 else 1.0
                val hrBpm: Int? = null
                val inExercise: Boolean? = null
                val sensorOk = true

                val tailLowCandidate = (abs(iobU) < 0.1) && (minutesSinceLastBolus >= 90) && (bgNow < minBg)
                if (tailLowCandidate) aapsLogger.debug(LTag.APS, "SIPP tail-low candidate: IOB≈0, no recent SMBs, BG<$minBg")

                val (baseDiaH, basePeakMin) = when (SippPrefs.insulinArchetype()) {
                    "RAPID"   -> 5.0f to 75
                    "FIASP"   -> 4.0f to 60
                    "LYUMJEV" -> 3.5f to 50
                    else      -> profile.dia.toFloat() to preferences.get(IntKey.InsulinOrefPeak)
                }

                sentinelController.applyEvidence(
                    nowMs = nowMs,
                    bgNow = bgNow,
                    bgPred = bgPred,
                    deltaPerMin = deltaPerMin,
                    iobU = iobU,
                    basalSuspendedMin = basalSuspendedMin,
                    siteAgeHours = siteAgeHours,
                    minutesSinceLastBolus = minutesSinceLastBolus,
                    hr = hrBpm,
                    inExercise = inExercise,
                    sensorOk = sensorOk,
                    baseDiaH = baseDiaH,
                    basePeakMin = basePeakMin
                )
            }.onFailure { /* never let SIPP crash dosing */ }
        }

        // -------- ISF selection (SIPP-first; then DS; else Profile) --------
        val baseIsfMgdl: Double = profile.getIsfMgdl("OpenAPSSMBPlugin")
        val dsPair = calculateVariableIsf(now, epsMultiplier)
        val dsIsf: Double? = dsPair.second
        val sippIsf: Double? = if (SippPrefs.enableIsf()) dynIsfResult.variableSensitivity else null

        val sensForJs: Double = when {
            SippPrefs.enableIsf() && sippIsf != null && sippIsf > 0.0                            -> sippIsf
            preferences.get(BooleanKey.ApsUseDynamicSensitivity) && dsIsf != null && dsIsf > 0.0 -> dsIsf
            else                                                                                 -> baseIsfMgdl
        }

        // Persist “instant used” ISF every run for UI
        runCatching { SippPrefs.saveLastInstantIsfMgdl(sensForJs, now, glucoseStatus.glucose, profile.getTargetLowMgdl()) }
        runCatching { if (dsIsf != null && dsIsf > 0.0) SippPrefs.saveLastRawInstantIsfMgdl(dsIsf, now) }

        // --------- FIRST-RUN SEED (prevents “—” on new phone) ----------
        if ((SippPrefs.enableBasal() || SippPrefs.enableMaxBasal())) {
            synthesizeIfMissing(
                profile = profile,
                usedIsfMgdl = sensForJs,
                deliveredBasalNow = tb?.convertedToAbsolute(now, profile) ?: activePlugin.activePump.baseBasalRate,
                minutesRunning = tb?.getPassedDurationToTimeInMinutes(now) ?: 0,
                minBg = minBg,
                isTempTarget = isTempTarget
            )
        }
        // ---------------------------------------------------------------

        // ==== Inject SIPP Instant Basal & Max Basal into the JS profile (with decay if stale) ====
        fun ageMin(ts: Long) = if (ts == 0L) 9999 else ((now - ts) / 60000L).toInt()

        val sippInstantBasal = if (SippPrefs.enableBasal()) SippPrefs.lastInstantBasalUph() else null
        val sippInstantBasalTs = SippPrefs.lastInstantBasalTsMs() ?: 0L
        val basalAge = ageMin(sippInstantBasalTs)
        val currentBasalForJs = when {
            sippInstantBasal != null && sippInstantBasal > 0.0 && basalAge <= 15     ->
                sippInstantBasal.coerceAtMost(hardLimits.maxBasal())

            sippInstantBasal != null && sippInstantBasal > 0.0 && basalAge in 16..60 -> {
                val frac = (basalAge - 15).toDouble() / (60 - 15).toDouble()
                val decayed = sippInstantBasal + (profile.getBasal() - sippInstantBasal) * frac
                decayed.coerceAtMost(hardLimits.maxBasal())
            }

            else                                                                     -> activePlugin.activePump.baseBasalRate
        }

        val sippMaxBasalForJs = if (SippPrefs.enableMaxBasal()) SippPrefs.lastMaxBasalUph() else null
        val sippMaxBasalTs = SippPrefs.lastMaxBasalTsMs() ?: 0L
        val maxAge = ageMin(sippMaxBasalTs)
        val constrainedMax = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value()
        val maxBasalForJs = when {
            sippMaxBasalForJs != null && sippMaxBasalForJs > 0.0 && maxAge <= 15     ->
                sippMaxBasalForJs.coerceAtMost(hardLimits.maxBasal())

            sippMaxBasalForJs != null && sippMaxBasalForJs > 0.0 && maxAge in 16..60 -> {
                val frac = (maxAge - 15).toDouble() / (60 - 15).toDouble()
                val decayed = sippMaxBasalForJs + (constrainedMax - sippMaxBasalForJs) * frac
                decayed.coerceAtMost(hardLimits.maxBasal())
            }

            else                                                                     -> constrainedMax
        }

        @Suppress("KotlinConstantConditions")
        val oapsProfile = OapsProfile(
            dia = 0.0,
            min_5m_carbimpact = 0.0,
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = maxBasalForJs,
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = sensForJs,
            autosens_adjust_targets = false,
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = currentBasalForJs,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) (dsIsf ?: 0.0) else 0.0,
            insulinDivisor = 0,
            TDD = calculateVariableIsf(now, epsMultiplier).second?.let { 1800.0 / it } ?: (dynIsfResult.tdd ?: 0.0)
        )

        val microBolusAllowed =
            constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()

        val flatBGsDetected = (bgQualityCheck.state == BgQualityCheck.State.FLAT)

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfMode:         ${preferences.get(BooleanKey.ApsUseDynamicSensitivity)}")

        determineBasalSMB.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity) && (dynIsfResult.tddPartsCalculated())
        ).also {
            val determineBasalResult = apsResultProvider.get().with(it)
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfile = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(maxFromBasalMultiplier, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)), this)

            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)

            // Apply SIPP Max Basal if enabled and available
            val sippMaxBasal = if (SippPrefs.enableMaxBasal()) SippPrefs.lastMaxBasalUph() else null
            if (sippMaxBasal != null && sippMaxBasal > 0.0) {
                val msg = "Limiting basal by SIPP Max Basal (" + String.format(Locale.getDefault(), "%.2f", sippMaxBasal) + " U/h)"
                absoluteRate.setIfSmaller(sippMaxBasal, msg, this)
            }
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        if (requiredKey != null && requiredKey != "absorption_smb_advanced") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapssmb_settings"
            title = rh.gs(R.string.openapssmb)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.ApsMaxBasal,
                    dialogMessage = R.string.openapsma_max_basal_summary,
                    title = R.string.openapsma_max_basal_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.ApsSmbMaxIob,
                    dialogMessage = R.string.openapssmb_max_iob_summary,
                    title = R.string.openapssmb_max_iob_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseDynamicSensitivity,
                    summary = R.string.use_dynamic_sensitivity_summary,
                    title = R.string.use_dynamic_sensitivity_title
                ).apply {
                    setOnPreferenceChangeListener { _, newValue ->
                        val on = newValue as Boolean
                        if (on && SippPrefs.enableIsf()) SippPrefs.setEnableIsf(false)
                        true
                    }
                }
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseAutosens,
                    title = R.string.openapsama_use_autosens
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = IntKey.ApsDynIsfAdjustmentFactor,
                    dialogMessage = R.string.dyn_isf_adjust_summary,
                    title = R.string.dyn_isf_adjust_title
                )
            )
            addPreference(
                AdaptiveUnitPreference(
                    ctx = context,
                    unitKey = UnitDoubleKey.ApsLgsThreshold,
                    dialogMessage = R.string.lgs_threshold_summary,
                    title = R.string.lgs_threshold_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsDynIsfAdjustSensitivity,
                    summary = R.string.dynisf_adjust_sensitivity_summary,
                    title = R.string.dynisf_adjust_sensitivity
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsSensitivityRaisesTarget,
                    summary = R.string.sensitivity_raises_target_summary,
                    title = R.string.sensitivity_raises_target_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsResistanceLowersTarget,
                    summary = R.string.resistance_lowers_target_summary,
                    title = R.string.resistance_lowers_target_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseSmb,
                    summary = R.string.enable_smb_summary,
                    title = R.string.enable_smb
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseSmbWithHighTt,
                    summary = R.string.enable_smb_with_high_temp_target_summary,
                    title = R.string.enable_smb_with_high_temp_target
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseSmbAlways,
                    summary = R.string.enable_smb_always_summary,
                    title = R.string.enable_smb_always
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseSmbWithCob,
                    summary = R.string.enable_smb_with_cob_summary,
                    title = R.string.enable_smb_with_cob
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseSmbWithLowTt,
                    summary = R.string.enable_smb_with_temp_target_summary,
                    title = R.string.enable_smb_with_temp_target
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseSmbAfterCarbs,
                    summary = R.string.enable_smb_after_carbs_summary,
                    title = R.string.enable_smb_after_carbs
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = IntKey.ApsMaxSmbFrequency,
                    title = R.string.smb_interval_summary
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = IntKey.ApsMaxMinutesOfBasalToLimitSmb,
                    title = R.string.smb_max_minutes_summary
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb,
                    dialogMessage = R.string.uam_smb_max_minutes,
                    title = R.string.uam_smb_max_minutes_summary
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.ApsUseUam,
                    summary = R.string.enable_uam_summary,
                    title = R.string.enable_uam
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = IntKey.ApsCarbsRequestThreshold,
                    dialogMessage = R.string.carbs_req_threshold_summary,
                    title = R.string.carbs_req_threshold
                )
            )
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_smb_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(
                    AdaptiveIntentPreference(
                        ctx = context,
                        intentKey = IntentKey.ApsLinkToDocs,
                        intent = Intent().apply {
                            action = Intent.ACTION_VIEW
                            data = rh.gs(R.string.openapsama_link_to_preference_json_doc).toUri()
                        },
                        summary = R.string.openapsama_link_to_preference_json_doc_txt
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = BooleanKey.ApsAlwaysUseShortDeltas,
                        summary = R.string.always_use_short_avg_summary,
                        title = R.string.always_use_short_avg
                    )
                )
                addPreference(
                    AdaptiveDoublePreference(
                        ctx = context,
                        doubleKey = DoubleKey.ApsMaxDailyMultiplier,
                        dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary,
                        title = R.string.openapsama_max_daily_safety_multiplier
                    )
                )
                addPreference(
                    AdaptiveDoublePreference(
                        ctx = context,
                        doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier,
                        dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary,
                        title = R.string.openapsama_current_basal_safety_multiplier
                    )
                )
            })
        }
    }
}

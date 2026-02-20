package app.aaps.plugins.aps.openAPSSippSMB

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Singleton
class DetermineBasalSippSMB @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy
) {

    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    fun round_basal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int = value.roundToInt()

    // we expect BG to rise or fall at the rate of BGI,
    // adjusted by the rate at which BG would need to rise /
    // fall to get eventualBG to target over 2 hours
    fun calculate_expected_delta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
        // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
        val fiveMinBlocks = (2 * 60) / 5
        val targetDelta = targetBg - eventualBg
        return /* expectedDelta */ round(bgi + (targetDelta / fiveMinBlocks), 1)
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")
    //DecimalFormat("0.#").format(profileUtil.fromMgdlToUnits(value))
    //if (profile.out_units === "mmol/L") round(value / 18, 1).toFixed(1);
    //else Math.round(value);

    fun enable_smb(
        profile: OapsProfile,
        microBolusAllowed: Boolean,
        meal_data: MealData,
        targetBgLow: Double,
        targetBgHigh: Double
    ): Boolean {
        // AAPS supports a target range: target_low (down) and target_high (up).
        // SMB enable/disable decisions should respect the bound being tested.
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && targetBgHigh > 100) {
            consoleError.add("SMB disabled due to high temptarget of ${convert_bg(targetBgLow)}–${convert_bg(targetBgHigh)}")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && meal_data.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${meal_data.mealCOB}")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (profile.enableSMB_after_carbs && meal_data.carbs != 0.0) {
            consoleError.add("SMB enabled for 6h after carb entry")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && profile.temptargetSet && targetBgLow < 100) {
            consoleError.add("SMB enabled for temptarget of ${convert_bg(targetBgLow)}–${convert_bg(targetBgHigh)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    private fun getMaxSafeBasal(profile: OapsProfile): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfile, rT: RT, currenttemp: CurrentTemp): RT {
        //var maxSafeBasal = Math.min(profile.max_basal, 3 * profile.max_daily_basal, 4 * profile.current_basal);

        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = round_basal(rate)
        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 && suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 && duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
            return rT
        }

        if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                    return rT
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                    return rT
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
                return rT
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
            return rT
        }
    }

    /**
     * Data class to hold peak scan results for requirement-aware SMB.
     */
    private data class PeakScanResult(
        val activeTraceType: String,         // "UAM", "COB", or "IOB"
        val peakPredBg: Double,              // highest BG in fast window
        val peakTimeMin: Int,                // time at peak (minutes from now)
        val minPredBgLow: Double,            // lowest BG in low horizon
        val requiredAdditionalInsulinU: Double, // insulin needed to bring peak to target
        val lowBrakeTriggered: Boolean       // whether low brake blocked SMB
    )

    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfile, autosens_data: AutosensResult, meal_data: MealData,
        microBolusAllowed: Boolean,
        currentTime: Long,
        flatBGsDetected: Boolean,
        dynIsfMode: Boolean,
        sippInstantIsfMode: Boolean = false,
        sippInstantIsfMgdlPerU: Double = 0.0,
        sippMaxSmbBolusU: Double = 0.5,
        sippMaxUamSmbBolusU: Double = 0.3,
        isSleepState: Boolean,
        sippPeakMinutes: Int
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )

        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
        val profile_current_basal = round_basal(profile.current_basal)
        val current_basal = profile_current_basal // alias for legacy naming used in safety checks
        var basal = profile_current_basal

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        val bg = glucose_status.glucose
        // TODO eliminate
        val noise = glucose_status.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }
        if (bg <= 10 || bg == 38.0 || noise >= 3 || minAgo > 12 || minAgo < -5 || (bg > 60 && flatBGsDetected)) {
            if (currenttemp.rate > basal) { // high temp is running
                rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = basal
                return rT
            } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) { //shorten long zero temps to 30m
                rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = 0.0
                return rT
            } else { //do nothing.
                rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${round(basal, 2)}U/hr; doing nothing. ")
                return rT
            }
        }

        // TODO eliminate
        val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver

        // AAPS has a target range: target_low (down) and target_high (up).
        // Use the MID target for insulin-need math, but keep bounds for range logic.
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg
        var target_bg = (min_bg + max_bg) / 2

        // Keep target_bg always derived from the current bounds.
        fun recomputeTargetBg(): Double {
            target_bg = (min_bg + max_bg) / 2
            return target_bg
        }

        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = 100 // evaluate high/low temptarget against 100, not scheduled target (which might change)
        // when temptarget is 160 mg/dL, run 50% basal (120 = 75%; 140 = 60%),  80 mg/dL with low_temptarget_lowers_sensitivity would give 1.5x basal, but is limited to autosens_max (1.2x by default)
        val halfBasalTarget = profile.half_basal_exercise_target

        if (dynIsfMode) {
            consoleError.add("---------------------------------------------------------")
            consoleError.add(" Dynamic ISF version 2.0 ")
            consoleError.add("---------------------------------------------------------")
        }

        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatio = c / (c + target_bg - normalTarget)
            // limit sensitivityRatio to profile.autosens_max (1.2x by default)
            sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
            sensitivityRatio = round(sensitivityRatio, 2)
            consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
        } else {
            sensitivityRatio = autosens_data.ratio
            consoleLog.add("Autosens ratio: $sensitivityRatio; ")
        }
        basal = profile.current_basal * sensitivityRatio
        basal = round_basal(basal)
        if (basal != profile_current_basal)
            consoleLog.add("Adjusting basal from $profile_current_basal to $basal; ")
        else
            consoleLog.add("Basal unchanged: $basal; ")

        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            //console.log("Temp Target set, not adjusting with autosens; ");
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                val oldTarget = target_bg
                recomputeTargetBg()
                if (oldTarget == target_bg)
                    consoleLog.add("target_bg unchanged: $target_bg; ")
                else
                    consoleLog.add("target_bg from $oldTarget to $target_bg (avg of min/max); ")
            }
        }

        val iobArray = iob_data_array
        val iob_data = iobArray[0]

        val tick: String

        tick = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))

        val sens = when {
            sippInstantIsfMode && sippInstantIsfMgdlPerU > 0.0 -> {
                consoleLog.add("ISF set by SIPP Instant: ${round(sippInstantIsfMgdlPerU, 1)}")
                sippInstantIsfMgdlPerU
            }

            dynIsfMode -> profile.variable_sens

            else       -> {
                val profile_sens = round(profile.sens, 1)
                val adjusted_sens = round(profile.sens / sensitivityRatio, 1)
                if (adjusted_sens != profile_sens) {
                    consoleLog.add("ISF from $profile_sens to $adjusted_sens")
                } else {
                    consoleLog.add("ISF unchanged: $adjusted_sens")
                }
                adjusted_sens
                //console.log(" (autosens ratio "+sensitivityRatio+")");
            }
        }
        consoleError.add("CR:${profile.carb_ratio}")

        // =====================================================================
        // F1: Treat negative basal debt / negative insulin activity as non-physiological in sleep / no-meal contexts.
        // Rationale: negative activity (from suspend/basal-debt artifacts) can inflate predictions and provoke insulin.
        // This is an INVARIANT clamp used only for prediction math (not reporting), and must work even if sleep fails.
        // Evidence (no new knobs):
        //  - explicit meal entry (carbs/COB) and/or deviation slope => digestion evidence
        //  - rising trend => allow negative activity to remain (can be physiological rebound)
        //  - sleep OR stable/no-rise + no digestion evidence => clamp negative activity to 0
        // =====================================================================
        val sippMealEntered = (meal_data.carbs > 0.0) || (meal_data.mealCOB > 0.0)
        val sippDigestionEvidence = sippMealEntered || (meal_data.slopeFromMaxDeviation > 0.0)

        val sippTrendRising = (glucose_status.shortAvgDelta > 6.0) || (glucose_status.delta > 6.0)
        val sippTrendStable = (abs(glucose_status.shortAvgDelta) <= 3.0) && (abs(glucose_status.delta) <= 3.0)

        // No-meal likely even if user never enters carbs: stable BG + no digestion evidence.
        val sippNoMealLikely = (!sippDigestionEvidence) && (!sippTrendRising) && sippTrendStable
        val sippSleepNoMealLikely = isSleepState && (!sippDigestionEvidence) && (!sippTrendRising)

        val sippClampNegActivity = sippSleepNoMealLikely || sippNoMealLikely


        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val activityForBgi = if (sippClampNegActivity && iob_data.activity < 0) 0.0 else iob_data.activity
        val bgi = round((-activityForBgi * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }

        // SIPP SAFETY: Sleep Band Guard
        // Definition: BG 4.0-6.0 mmol/L (72-108 mg/dL), flat trend, and Sleep/HighSensitivity state.
        val isSleepBand = isSleepState &&
            bg >= 72 && bg <= 108 &&
            Math.abs(glucose_status.delta) < 3

        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        //
        // SIPP SAFETY: avoid the "negative IOB vacuum" when BG is below target.
        // Negative net IOB can inflate naive_eventualBG (bg - iob*sens) and provoke dosing while still below target.
        // We clamp negative IOB to 0 for dosing calculations whenever BG < target_bg (and always in the sleep band guard).
        val clampNegativeIobForDosing = (bg < target_bg) || sippClampNegActivity
        val effectiveIOB = when {
            iob_data.iob < 0 && (isSleepBand || clampNegativeIobForDosing) -> 0.0
            else -> iob_data.iob
        }

        val naive_eventualBG =
            if (dynIsfMode) {
                round(bg - (effectiveIOB * sens), 0)
            } else {
                if (effectiveIOB > 0) {
                    round(bg - (effectiveIOB * sens), 0)
                } else {
                    // If IOB is negative and we didn't clamp it (e.g., BG above target), be conservative:
                    // use the lower of sens and profile.sens, unless Instant ISF is active (then keep sens consistent).
                    val negIobSens = if (sippInstantIsfMode) sens else min(sens, profile.sens)
                    round(bg - (effectiveIOB * negIobSens), 0)
                }
            }

        // and adjust it for the deviation above
        var eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // Advanced target adjustments act on the *bounds*; keep target_bg as the average of min/max.
            // with target=100, as BG rises from 100 to 160, adjusted bounds drop toward 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)

            // if eventualBG, naive_eventualBG, and min_bg aren't all above adjustedMinBG, don’t use it
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog.add("min_bg unchanged: $min_bg; ")
            }

            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }

            val oldTarget = target_bg
            recomputeTargetBg()
            if (oldTarget == target_bg) {
                consoleLog.add("target_bg unchanged: $target_bg; ")
            } else {
                consoleLog.add("target_bg from $oldTarget to $target_bg (avg of min/max); ")
            }
        }

        val expectedDelta = calculate_expected_delta(target_bg, eventualBG, bgi)

        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError.add("Threshold set from ${convert_bg(threshold)} to ${convert_bg(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }

        //console.error(reservoir_data);

        rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = profile.variable_sens
        )

        // generate predicted future BGs based on IOB, COB, and current absorption rate

        var COBpredBGs = mutableListOf<Double>()
        var aCOBpredBGs = mutableListOf<Double>()
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()
        COBpredBGs.add(bg)
        aCOBpredBGs.add(bg)
        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)

        var enableSMB = enable_smb(profile, microBolusAllowed, meal_data, min_bg, max_bg)

        // SIPP SAFETY: If current BG is below target, do not microbolus.
        // (Basal adjustments are still allowed.)
        if (enableSMB && bg < target_bg) {
            consoleError.add("SIPP Safety: BG ${convert_bg(bg)} < target ${convert_bg(target_bg)} - disabling SMB")
            enableSMB = false
        }

        // enable UAM (if enabled in preferences)
        val enableUAM = profile.enableUAM

        //console.error(meal_data);
        // carb impact and duration are 0 unless changed below
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        // ISF (mg/dL/U) / CR (g/U) = CSF (mg/dL/g)

        // TODO: remove commented-out code for old behavior
        //if (profile.temptargetSet) {
        // if temptargetSet, use unadjusted profile.sens to allow activity mode sensitivityRatio to adjust CR
        //var csf = profile.sens / profile.carb_ratio;
        //} else {
        // otherwise, use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments
        // so that autotuned CR is still in effect even when basals and ISF are being adjusted by autosens
        //var csf = sens / profile.carb_ratio;
        //}
        // use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments so that
        // autotuned CR is still in effect even when basals and ISF are being adjusted by TT or autosens
        // this avoids overdosing insulin for large meals when low temp targets are active
        val csf = sens / profile.carb_ratio
        consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI
        }
        var remainingCATimeMin = 3.0 // h; duration of expected not-yet-observed carb absorption
        // adjust remainingCATime (instead of CR) for autosens if sensitivityRatio defined
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        // 20 g/h means that anything <= 60g will get a remainingCATimeMin, 80g will get 4h, and 120g 6h
        // when actual absorption ramps up it will take over from remainingCATime
        val assumedCarbAbsorptionRate = 20 // g/h; maximum rate to assume carbs will absorb if no CI observed
        var remainingCATime = remainingCATimeMin
        if (meal_data.carbs != 0.0) {
            // if carbs * assumedCarbAbsorptionRate > remainingCATimeMin, raise it
            // so <= 90g is assumed to take 3h, and 120g=4h
            remainingCATimeMin = Math.max(remainingCATimeMin, meal_data.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((systemTime - meal_data.lastCarbTime) / 60000.0)
            //console.error(meal_data.lastCarbTime, lastCarbAge);

            val fractionCOBAbsorbed = (meal_data.carbs - meal_data.mealCOB) / meal_data.carbs
            remainingCATime = remainingCATimeMin + 1.5 * lastCarbAge / 60
            remainingCATime = round(remainingCATime, 1)
            //console.error(fractionCOBAbsorbed, remainingCATimeAdjustment, remainingCATime)
            consoleError.add("Last carbs " + lastCarbAge + "minutes ago; remainingCATime:" + remainingCATime + "hours;" + round(fractionCOBAbsorbed * 100) + "% carbs absorbed")
        }

        // calculate the number of carbs absorbed over remainingCATime hours at current CI
        // CI (mg/dL/5m) * (5m)/5 (m) * 60 (min/hr) * 4 (h) / 2 (linear decay factor) = total carb impact (mg/dL)
        val totalCI = Math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, meal_data.mealCOB - totalCA)
        remainingCarbs = Math.min(remainingCarbsCap.toDouble(), remainingCarbs)
        // assume remainingCarbs will absorb in a /\ shaped bilinear curve
        // peaking at remainingCATime / 2 and ending at remainingCATime hours
        // area of the /\ triangle is the same as a remainingCIpeak-height rectangle out to remainingCATime/2
        // remainingCIpeak (mg/dL/5m) = remainingCarbs (g) * CSF (mg/dL/g) * 5 (m/5m) * 1h/60m / (remainingCATime/2) (h)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        if (remainingCIpeak.isNaN()) {
            throw Exception("remainingCarbs=$remainingCarbs remainingCATime=$remainingCATime profile.remainingCarbsCap=${profile.remainingCarbsCap} csf=$csf")
        }
        //console.error(profile.min_5m_carbimpact,ci,totalCI,totalCA,remainingCarbs,remainingCI,remainingCATime);

        // calculate peak deviation in last hour, and slope from that to current deviation
        val slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation, 2)
        // calculate lowest deviation in last hour, and slope from that to current deviation
        val slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation, 2)
        // assume deviations will drop back down at least at 1/3 the rate they ramped up
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        //console.error(slopeFromMaxDeviation);

        val aci = 10
        //5m data points = g * (1U/10g) * (40mg/dL/1U) / (mg/dL/5m)
        // duration (in 5m data points) = COB (g) * CSF (mg/dL/g) / ci (mg/dL/5m)
        // limit cid to remainingCATime hours: the reset goes to remainingCI
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, meal_data.mealCOB * csf / ci))
        }
        val acid = max(0.0, meal_data.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        consoleError.add("Carb Impact: $ci mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        //console.error("Accel. Carb Impact:",aci,"mg/dL per 5m; ACI Duration:",round(acid*5/60*2,1),"hours");
        var minIOBPredBG = 999.0
        var minCOBPredBG = 999.0
        var minUAMPredBG = 999.0
        var minGuardBG: Double
        var minCOBGuardBG = 999.0
        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var minPredBG: Double
        var avgPredBG: Double
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg
        var maxCOBPredBG = bg
        //var maxUAMPredBG = bg
        //var maxPredBG = bg;
        //var eventualPredBG = bg
        val lastIOBpredBG: Double
        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null
        //var lastZTpredBG: Int
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null
        var COBpredBG: Double? = null
        var aCOBpredBG: Double?
        iobArray.forEach { iobTick ->
            //console.error(iobTick);
            val tickActivity = if (sippClampNegActivity && iobTick.activity < 0) 0.0 else iobTick.activity
            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            val tickZtActivity = if (sippClampNegActivity && iobTick.iobWithZeroTemp!!.activity < 0) 0.0 else iobTick.iobWithZeroTemp!!.activity
            val predBGI: Double = round((-tickActivity * sens * 5), 2)
            val IOBpredBGI: Double =
                if (dynIsfMode) round((-tickActivity * (1800 / (profile.TDD * (ln((max(IOBpredBGs[IOBpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            // try to find where is crashing https://console.firebase.google.com/u/0/project/androidaps-c34f8/crashlytics/app/android:info.nightscout.androidaps/issues/950cdbaf63d545afe6d680281bb141e5?versions=3.3.0-dev-d%20(1500)&time=last-thirty-days&types=crash&sessionEventKey=673BF7DD032300013D4704707A053273_2017608123846397475
            if (iobTick.iobWithZeroTemp!!.activity.isNaN() || sens.isNaN())
                fabricPrivacy.logCustom("iobTick.iobWithZeroTemp!!.activity=${iobTick.iobWithZeroTemp!!.activity} sens=$sens")
            val predZTBGI =
                if (dynIsfMode) round((-tickZtActivity * (1800 / (profile.TDD * (ln((max(ZTpredBGs[ZTpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else round((-tickZtActivity * sens * 5), 2)
            val predUAMBGI =
                if (dynIsfMode) round((-tickActivity * (1800 / (profile.TDD * (ln((max(UAMpredBGs[UAMpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for COBpredBGs, predicted carb impact drops linearly from current carb impact down to zero
            // eventually accounting for all carbs (if they can be absorbed over DIA)
            val predCI: Double = max(0.0, max(0.0, ci) * (1 - COBpredBGs.size / max(cid * 2, 1.0)))
            val predACI = max(0.0, max(0, aci) * (1 - COBpredBGs.size / max(acid * 2, 1.0)))
            // if any carbs aren't absorbed after remainingCATime hours, assume they'll absorb in a /\ shaped
            // bilinear curve peaking at remainingCIpeak at remainingCATime/2 hours (remainingCATime/2*12 * 5m)
            // and ending at remainingCATime h (remainingCATime*12 * 5m intervals)
            val intervals = Math.min(COBpredBGs.size.toDouble(), ((remainingCATime * 12) - COBpredBGs.size))
            val remainingCI = Math.max(0.0, intervals / (remainingCATime / 2 * 12) * remainingCIpeak)
            if (remainingCI.isNaN()) {
                throw Exception("remainingCI=$remainingCI intervals=$intervals remainingCIpeak=$remainingCIpeak")
            }
            remainingCItotal += predCI + remainingCI
            remainingCIs.add(round(remainingCI))
            predCIs.add(round(predCI))
            //console.log(round(predCI,1)+"+"+round(remainingCI,1)+" ");
            COBpredBG = COBpredBGs[COBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predCI + remainingCI
            aCOBpredBG = aCOBpredBGs[aCOBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predACI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            //console.error(predBGI, predCI, predUCI);
            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 48) IOBpredBGs.add(IOBpredBG)
            if (COBpredBGs.size < 48) COBpredBGs.add(COBpredBG)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(aCOBpredBG)
            if (UAMpredBGs.size < 48) UAMpredBGs.add(UAMpredBG)
            if (ZTpredBGs.size < 48) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (COBpredBG < minCOBGuardBG) minCOBGuardBG = round(COBpredBG).toDouble()
            if (UAMpredBG < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            val insulinPeakTime = 0 // SIPP SAFETY: always scan the full horizon for minima
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            //console.error(insulinPeakTime, insulinPeak5m, profile.insulinPeakTime, profile.curve);

            // SIPP SAFETY: no “wait until peak” delay for minPred scanning (insulinPeakTime delay disabled).
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            // SIPP SAFETY: COB minPred scanning uses the same no-delay horizon; UAM minPred remains 60m (UAM is noisier).
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && (COBpredBG < minCOBPredBG)) minCOBPredBG = round(COBpredBG, 0)
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBG > maxIOBPredBG) maxCOBPredBG = COBpredBG
            if (enableUAM && UAMpredBGs.size > 12 && (UAMpredBG < minUAMPredBG)) minUAMPredBG = round(UAMpredBG, 0)
            //if (enableUAM && UAMpredBG!! > maxIOBPredBG) maxUAMPredBG = UAMpredBG!!
        }
        // set eventualBG to include effect of carbs
        //console.error("PredBGs:",JSON.stringify(predBGs));
        if (meal_data.mealCOB > 0) {
            consoleError.add("predCIs (mg/dL/5m):" + predCIs.joinToString(separator = " "))
            consoleError.add("remainingCIs:      " + remainingCIs.joinToString(separator = " "))
        }
        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }
        if (meal_data.mealCOB > 0) {
            aCOBpredBGs = aCOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in aCOBpredBGs.size - 1 downTo 13) {
                if (aCOBpredBGs[i - 1] != aCOBpredBGs[i]) break
                else aCOBpredBGs.removeAt(aCOBpredBGs.lastIndex)
            }
        }
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            COBpredBGs = COBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in COBpredBGs.size - 1 downTo 13) {
                if (COBpredBGs[i - 1] != COBpredBGs[i]) break
                else COBpredBGs.removeAt(COBpredBGs.lastIndex)
            }
            rT.predBGs?.COB = COBpredBGs.map { it.toInt() }
            lastCOBpredBG = COBpredBGs[COBpredBGs.size - 1]
            eventualBG = max(eventualBG, round(COBpredBGs[COBpredBGs.size - 1], 0))
        }
        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }

        consoleError.add("UAM Impact: $uci mg/dL per 5m; UAM Duration: $UAMduration hours")
        consoleLog.add("EventualBG is $eventualBG ;")

        minIOBPredBG = max(39.0, minIOBPredBG)
        minCOBPredBG = max(39.0, minCOBPredBG)
        minUAMPredBG = max(39.0, minUAMPredBG)
        minPredBG = round(minIOBPredBG, 0)

        val fSensBG = min(minPredBG, bg)

        var future_sens = 0.0
        if (dynIsfMode) {
            if (bg > target_bg && glucose_status.delta < 3 && glucose_status.delta > -3 && glucose_status.shortAvgDelta > -3 && glucose_status.shortAvgDelta < 3 && eventualBG > target_bg && eventualBG
                < bg
            ) {
                future_sens = (1800 / (ln((((fSensBG * 0.5) + (bg * 0.5)) / profile.insulinDivisor) + 1) * profile.TDD))
                future_sens = round(future_sens, 1)
                consoleLog.add("Future state sensitivity is $future_sens based on eventual and current bg due to flat glucose level above target")
                rT.reason.append("Dosing sensitivity: $future_sens using eventual BG;")
            } else if (glucose_status.delta > 0 && eventualBG > target_bg || eventualBG > bg) {
                future_sens = (1800 / (ln((bg / profile.insulinDivisor) + 1) * profile.TDD))
                future_sens = round(future_sens, 1)
                consoleLog.add("Future state sensitivity is $future_sens using current bg due to small delta or variation")
                rT.reason.append("Dosing sensitivity: $future_sens using current BG;")
            } else {
                future_sens = (1800 / (ln((fSensBG / profile.insulinDivisor) + 1) * profile.TDD))
                future_sens = round(future_sens, 1)
                consoleLog.add("Future state sensitivity is $future_sens based on eventual bg due to -ve delta")
                rT.reason.append("Dosing sensitivity: $future_sens using eventual BG;")
            }
        }

        // =====================================================================
        // SIPP Phase 1: Requirement-aware SMB with peak scan + absolute cap
        // =====================================================================
        // Compute guard horizons from peak time
        val sippGuardHighMinutes = (sippPeakMinutes + 30).coerceIn(120, 240)
        val sippGuardLowMinutes = (2 * sippPeakMinutes).coerceIn(120, 240)

        // Fast window for peak scan: peak+30, clamped to 90..sippGuardHighMinutes
        val fastWindowMinutes = (sippPeakMinutes + 30).coerceIn(90, sippGuardHighMinutes)

        // A) Select active prediction trace: UAM > COB > IOB (but suppress phantom UAM during sleep rebound)
        // Use the same logic the algorithm uses to decide between prediction sources
        val hasUAM = enableUAM && minUAMPredBG < 999
        val hasCOB = (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) && minCOBPredBG < 999

        // ---- SIPP Sleep inference (no new user knobs) ----
        // Goal:
        // 1) During sleep with COB=0, do NOT treat UAM spikes from basal-debt / rebound as meal digestion (prevents large SMBs after hypos).
        // 2) Still allow prolonged digestion at night (COB may be 0) when evidence is strong and sustained.
        // Sleep state is passed in from OpenAPSSippSMBPlugin (Manual or Auto sleep).
        // Auto-sleep is derived from Sentinel activity fusion; we intentionally do NOT add a time-of-day fallback here.
        val sippIsSleep = isSleepState

// How much "basal debt" (negative IOB) exists (in U and in hours of current basal).
        val sippBasalDebtU = max(0.0, -iob_data.iob)
        val sippBasalDebtHours = if (current_basal > 0) (sippBasalDebtU / current_basal) else 0.0

        val lastBolusAgeMinutes =
            if (iob_data.lastBolusTime > 0) ((systemTime - iob_data.lastBolusTime) / 60000.0) else Double.POSITIVE_INFINITY
        val sippRecentBolusWithinPeak = lastBolusAgeMinutes <= sippPeakMinutes

        // Sleep + COB==0 + no recent bolus near the model peak => default to "SleepNoMeal".
        val sippSleepNoMeal = sippIsSleep && meal_data.mealCOB <= 0.0 && !sippRecentBolusWithinPeak

        // Guard-based low-risk signal (uses prediction math already computed above; no delay).
        // If the IOB/ZT guard predicts dipping below max(threshold,target), treat any UAM rise as rebound-risk.
        val sippGuardLowRisk = min(minIOBGuardBG, minZTGuardBG) < max(threshold, target_bg)

        // Model-derived "sustained window" for digestion inference:
        // use ~half the PK/PD peak time, clamped to 60–120 min so it works across insulins.
        val sippSustainedWindowMinutes = (sippPeakMinutes / 2.0).coerceIn(60.0, 120.0)
        val sippSustainedIdx = min(UAMpredBGs.lastIndex, (sippSustainedWindowMinutes / 5.0).toInt())

        // Credible-UAM score (majority vote of existing signals).
        var sippUamCredibleScore = 0
        if (glucose_status.shortAvgDelta > 0) sippUamCredibleScore++
        if (glucose_status.delta > 0) sippUamCredibleScore++
        if (deviation > 0) sippUamCredibleScore++
        if (meal_data.slopeFromMaxDeviation > 0) sippUamCredibleScore++
        if (glucose_status.shortAvgDelta > expectedDelta) sippUamCredibleScore++

        // F3+F5 helper: BG-line vs IOB-model-line mismatch ("unmodeled upward pressure").
        // Captures unannounced carbs, delayed digestion, dawn/stress hormones — but must NOT trigger during rebound/basal-debt artifacts.
        val sippLineWindowMinutes = (sippPeakMinutes / 3.0).coerceIn(15.0, 60.0)
        val sippLineIdx = min(IOBpredBGs.lastIndex, (sippLineWindowMinutes / 5.0).toInt())
        val sippIobLine = if (IOBpredBGs.isNotEmpty() && sippLineIdx >= 0) IOBpredBGs[sippLineIdx] else bg
        val sippBgLine = bg + (glucose_status.shortAvgDelta * sippLineIdx)
        val sippLinePressureUp =
            (glucose_status.shortAvgDelta > expectedDelta) &&
                (deviation > 0) &&
                (sippBgLine > max_bg) &&
                (sippIobLine <= max_bg) &&
                !sippGuardLowRisk &&
                (sippBasalDebtHours < 1.0)

        if (sippLinePressureUp) {
            // Strong evidence of real upward glucose pressure; increases meal-likelihood (F3) safely under F2.
            sippUamCredibleScore++
            consoleError.add(
                "SIPP linePressureUp: win=${round(sippLineWindowMinutes, 1)}m bgLine=${convert_bg(sippBgLine)} > max=${convert_bg(max_bg)} " +
                    "while iobLine=${convert_bg(sippIobLine)} <= max; debtH=${round(sippBasalDebtHours, 2)} guardLowRisk=$sippGuardLowRisk => +1 UAM cred"
            )
        }

        val sippUamCredible = sippUamCredibleScore >= 3

        // "Sustained high" in early window means: even the MIN of UAM prediction stays above max target.
        val sippUamMinInSustainedWindow =
            if (hasUAM && UAMpredBGs.isNotEmpty() && sippSustainedIdx >= 0)
                (0..sippSustainedIdx).minOf { UAMpredBGs[it] }
            else
                0.0

        val sippUamSustainedHigh = hasUAM && (sippUamMinInSustainedWindow > max_bg)
        // Phantom-UAM detection (no new knobs):
        // If COB=0 + negative IOB (basal debt) + BG not high, but UAM predicts much higher than IOB trace,
        // treat that UAM rise as rebound/artifact and block SMB.
        val sippUamPred = UAMpredBG ?: 0.0
        val sippIobPred = IOBpredBG
        val sippUamDivergesFromIob =
            hasUAM && (sippUamPred > 0.0) && (sippIobPred > 0.0) &&
                ((sippUamPred - sippIobPred) > (max_bg - target_bg))
        val sippPhantomUamLikely =
            (meal_data.mealCOB <= 0.0) && (iob_data.iob < 0.0) && (bg <= max_bg) && sippUamDivergesFromIob

        // Sleep rebound likelihood:
        // - COB=0
        // - in/near target band (<= max_bg)
        // - large basal debt (~>= 1h of basal missed)
        // - guard indicates low-risk
        // This combination matches "post-hypo rebound / suspension artifact" nights.
        val sippSleepReboundLikely =
            sippIsSleep &&
                meal_data.mealCOB <= 0.0 &&
                (bg <= max_bg) &&
                (sippBasalDebtHours >= 1.0) &&
                sippGuardLowRisk &&
                (glucose_status.shortAvgDelta > 0 || glucose_status.delta > 0)

        // Allow prolonged digestion at night even with COB=0 only if:
        // - BG is already above max_bg
        // - guard does NOT predict a low (so we aren't in rebound risk)
        // - UAM evidence is both credible and sustained-high
        val sippSleepAllowUamDigestion =
            sippIsSleep &&
                meal_data.mealCOB <= 0.0 &&
                (bg > max_bg) &&
                !sippGuardLowRisk &&
                sippUamCredible &&
                sippUamSustainedHigh && !sippPhantomUamLikely

        // UAM is allowed when:
        // - daytime OR (sleep but not SleepNoMeal) OR (sleep digestion override),
        // AND we are not in the rebound-likely state.
        val sippUamAllowedForDigestion =
            hasUAM &&
                (!sippSleepNoMeal || sippSleepAllowUamDigestion) &&
                !sippSleepReboundLikely && !sippPhantomUamLikely

        // Sleep SMB should be blocked if we are in "SleepNoMeal" without digestion override, especially during rebound.
        val sippSleepSmbBlocked =
            sippIsSleep &&
                meal_data.mealCOB <= 0.0 &&
                !sippSleepAllowUamDigestion &&
                (bg <= max_bg || sippSleepReboundLikely || sippPhantomUamLikely)

        // Recovery SMB block even if Sleep mode isn't enabled.
        val sippRecoverySmbBlocked =
            (meal_data.mealCOB <= 0.0) &&
                (bg <= max_bg) &&
                !sippSleepAllowUamDigestion &&
                (sippSleepReboundLikely || sippPhantomUamLikely)
        // F3: SMB gating — only allow SMB when meal likelihood is real (works even when carbs are not entered).
        // Meal-likelihood is true if:
        //  - COB model active OR carb window active, OR
        //  - UAM digestion is allowed AND credible, OR
        //  - BG-line shows real upward pressure vs IOB-model (linePressureUp).
        val sippCarbWindowActive = (meal_data.carbs != 0.0)
        val sippMealLikelyForSmb =
            hasCOB || sippCarbWindowActive ||
                (sippUamAllowedForDigestion && sippUamCredible) ||
                sippLinePressureUp

        val sippMealGateSmbBlocked = !sippMealLikelyForSmb

        // F4: Recovery hold after lows (model-derived, no new knob):
        // In no-meal contexts, if guard predicts dipping below TARGET (stricter than threshold), block SMB.
        val sippRecoveryHoldSmbBlocked =
            sippMealGateSmbBlocked &&
                (bg <= max_bg) &&
                (min(minIOBGuardBG, minZTGuardBG) < target_bg)

        if (enableSMB && (sippMealGateSmbBlocked || sippRecoveryHoldSmbBlocked)) {
            enableSMB = false
            rT.reason.append(
                if (sippRecoveryHoldSmbBlocked)
                    "SIPP F4: RecoveryHold blocks SMB (minPred<target, no-meal). "
                else
                    "SIPP F3: No-meal likelihood blocks SMB. "
            )
        }

        val sippSmbBlocked =
            sippSleepSmbBlocked || sippRecoverySmbBlocked || sippMealGateSmbBlocked || sippRecoveryHoldSmbBlocked


        val (activePredictions, activeTraceType) = when {
            sippUamAllowedForDigestion -> UAMpredBGs to "UAM"
            hasCOB -> COBpredBGs to "COB"
            else   -> IOBpredBGs to "IOB"
        }
        // Guard against empty predictions (should not happen, but be safe)
        val peakScanResult: PeakScanResult? = if (activePredictions.isEmpty()) {
            consoleError.add("SIPP Peak: no predictions available, skipping peak scan")
            null
        } else {
            // B) Peak scan: find max BG in fast window (index-safe)
            val endIdxHigh = min(activePredictions.lastIndex, fastWindowMinutes / 5)
            var peakPredBg = activePredictions[0]
            var peakIdx = 0
            for (i in 0..endIdxHigh) {
                if (activePredictions[i] > peakPredBg) {
                    peakPredBg = activePredictions[i]
                    peakIdx = i
                }
            }
            val peakTimeMin = peakIdx * 5

            // C) Low scan: find min BG in low horizon (for existing low brake)
            val endIdxLow = min(activePredictions.lastIndex, sippGuardLowMinutes / 5)
            var minPredBgLow = activePredictions[0]
            for (i in 0..endIdxLow) {
                if (activePredictions[i] < minPredBgLow) {
                    minPredBgLow = activePredictions[i]
                }
            }

            // D) Compute requiredAdditionalInsulinU
            // SIPP-first ISF for demand conversion:
            // 1) SIPP instant ISF (if enabled and valid), else
            // 2) dynISF future_sens (if enabled and valid), else
            // 3) profile sensitivity (sens).
            val demandIsf = when {
                sippInstantIsfMode && sippInstantIsfMgdlPerU > 0 -> sippInstantIsfMgdlPerU
                dynIsfMode && future_sens > 0 -> future_sens
                else                          -> sens
            }

            // Demand is defined from the ACTIVE predicted peak above target_bg (not min_bg).
            val demandTargetBg = target_bg
            val digestionEvidence = hasCOB || sippUamAllowedForDigestion
            val demandBg = if (digestionEvidence) peakPredBg else (activePredictions.lastOrNull() ?: peakPredBg)
            val requiredAdditionalInsulinU = if (demandIsf > 0) {
                max(0.0, (demandBg - demandTargetBg) / demandIsf)
            } else 0.0

            // E) Determine if low brake should trigger (existing behavior check)
            // Low brake triggers if minPredBgLow < threshold
            val lowBrakeTriggered = minPredBgLow < threshold

            // H) Concise log line for peak scan (round only for display)
            consoleError.add(
                "SIPP Peak: $activeTraceType peak=${convert_bg(peakPredBg)}@${peakTimeMin}m, " +
                    "reqU=${round(requiredAdditionalInsulinU, 2)}, " +
                    "minLow=${convert_bg(minPredBgLow)}, lowBrake=$lowBrakeTriggered"
            )

            // Store peak scan result (full precision, no rounding)
            PeakScanResult(
                activeTraceType = activeTraceType,
                peakPredBg = peakPredBg,
                peakTimeMin = peakTimeMin,
                minPredBgLow = minPredBgLow,
                requiredAdditionalInsulinU = requiredAdditionalInsulinU,
                lowBrakeTriggered = lowBrakeTriggered
            )
        }

        val fractionCarbsLeft: Double = when {
            meal_data.carbs > 0.0 -> (meal_data.mealCOB / meal_data.carbs).coerceIn(0.0, 1.0)
            meal_data.mealCOB > 0.0 -> 1.0
            else                  -> 0.0
        }
        // if we have COB and UAM is enabled, average both
        if (minUAMPredBG < 999 && minCOBPredBG < 999) {
            // weight COBpredBG vs. UAMpredBG based on how many carbs remain as COB
            avgPredBG = round((1 - fractionCarbsLeft) * UAMpredBG!! + fractionCarbsLeft * COBpredBG!!, 0)
            // if UAM is disabled, average IOB and COB
        } else if (minCOBPredBG < 999) {
            avgPredBG = round((IOBpredBG + COBpredBG!!) / 2.0, 0)
            // if we have UAM but no COB, average IOB and UAM
        } else if (minUAMPredBG < 999) {
            avgPredBG = round((IOBpredBG + UAMpredBG!!) / 2.0, 0)
        } else {
            avgPredBG = round(IOBpredBG, 0)
        }
        // if avgPredBG is below minZTGuardBG, bring it up to that level
        if (minZTGuardBG > avgPredBG) {
            avgPredBG = minZTGuardBG
        }

        // if we have both minCOBGuardBG and minUAMGuardBG, blend according to fractionCarbsLeft
        if ((cid > 0.0 || remainingCIpeak > 0)) {
            if (enableUAM) {
                minGuardBG = fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG
            } else {
                minGuardBG = minCOBGuardBG
            }
        } else if (enableUAM) {
            minGuardBG = minUAMGuardBG
        } else {
            minGuardBG = minIOBGuardBG
        }
        minGuardBG = round(minGuardBG, 0)
        //console.error(minCOBGuardBG, minUAMGuardBG, minIOBGuardBG, minGuardBG);

        var minZTUAMPredBG = minUAMPredBG
        // if minZTGuardBG is below threshold, bring down any super-high minUAMPredBG by averaging
        // this helps prevent UAM from giving too much insulin in case absorption falls off suddenly
        if (minZTGuardBG < threshold) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
            // if minZTGuardBG is between threshold and target, blend in the averaging
        } else if (minZTGuardBG < target_bg) {
            // target 100, threshold 70, minZTGuardBG 85 gives 50%: (85-70) / (100-70)
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            val blendedMinZTGuardBG = minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)
            minZTUAMPredBG = (minUAMPredBG + blendedMinZTGuardBG) / 2.0
            //minZTUAMPredBG = minUAMPredBG - target_bg + minZTGuardBG;
            // if minUAMPredBG is below minZTGuardBG, bring minUAMPredBG up by averaging
            // this allows more insulin if lastUAMPredBG is below target, but minZTGuardBG is still high
        } else if (minZTGuardBG > minUAMPredBG) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        }
        minZTUAMPredBG = round(minZTUAMPredBG, 0)
        //console.error("minUAMPredBG:",minUAMPredBG,"minZTGuardBG:",minZTGuardBG,"minZTUAMPredBG:",minZTUAMPredBG);
        // if any carbs have been entered recently
        if (meal_data.carbs != 0.0) {

            // if UAM is disabled, use max of minIOBPredBG, minCOBPredBG
            if (!enableUAM && minCOBPredBG < 999) {
                minPredBG = round(max(minIOBPredBG, minCOBPredBG), 0)
                // if we have COB, use minCOBPredBG, or blendedMinPredBG if it's higher
            } else if (minCOBPredBG < 999) {
                // calculate blendedMinPredBG based on how many carbs remain as COB
                val blendedMinPredBG = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                // if blendedMinPredBG > minCOBPredBG, use that instead
                minPredBG = round(max(minIOBPredBG, max(minCOBPredBG, blendedMinPredBG)), 0)
                // if carbs have been entered, but have expired, use minUAMPredBG
            } else if (enableUAM) {
                minPredBG = minZTUAMPredBG
            } else {
                minPredBG = minGuardBG
            }
            // in pure UAM mode, use the higher of minIOBPredBG,minUAMPredBG
        } else if (enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }
        // make sure minPredBG isn't higher than avgPredBG
        minPredBG = min(minPredBG, avgPredBG)

        consoleLog.add("minPredBG: $minPredBG minIOBPredBG: $minIOBPredBG minZTGuardBG: $minZTGuardBG")
        if (minCOBPredBG < 999) {
            consoleLog.add(" minCOBPredBG: $minCOBPredBG")
        }
        if (minUAMPredBG < 999) {
            consoleLog.add(" minUAMPredBG: $minUAMPredBG")
        }
        consoleError.add(" avgPredBG: $avgPredBG COB: ${meal_data.mealCOB} / ${meal_data.carbs}")
        // But if the COB line falls off a cliff, don't trust UAM too much:
        // use maxCOBPredBG if it's been set and lower than minPredBG
        if (maxCOBPredBG > bg) {
            minPredBG = min(minPredBG, maxCOBPredBG)
        }

        rT.COB = meal_data.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_bg(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )
        if (lastCOBpredBG != null) {
            rT.reason.append(", COBpredBG " + convert_bg(lastCOBpredBG.toDouble()))
        }
        if (lastUAMpredBG != null) {
            rT.reason.append(", UAMpredBG " + convert_bg(lastUAMpredBG.toDouble()))
        }
        rT.reason.append("; ")
        // use naive_eventualBG if above 40, but switch to minGuardBG if both eventualBGs hit floor of 39
        var carbsReqBG = naive_eventualBG
        if (carbsReqBG < 40) {
            carbsReqBG = min(minGuardBG, carbsReqBG)
        }
        var bgUndershoot: Double = threshold - carbsReqBG
        // calculate how long until COB (or IOB) predBGs drop below min_bg
        var minutesAboveMinBG = 240
        var minutesAboveThreshold = 240
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], min_bg);
                if (COBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], threshold);
                if (COBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        } else {
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], min_bg);
                if (IOBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], threshold);
                if (IOBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        }

        if (enableSMB && minGuardBG < threshold) {
            consoleError.add("minGuardBG ${convert_bg(minGuardBG)} projected below ${convert_bg(threshold)} - disabling SMB")
            //rT.reason += "minGuardBG "+minGuardBG+"<"+threshold+": SMB disabled; ";
            enableSMB = false
        }
        if (maxDelta > 0.20 * bg) {
            consoleError.add("maxDelta ${convert_bg(maxDelta)} > 20% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg(maxDelta) + " > 20% of BG " + convert_bg(bg) + ": SMB disabled; ")
            enableSMB = false
        }

        consoleError.add("BG projected to remain above ${convert_bg(min_bg)} for $minutesAboveMinBG minutes")
        if (minutesAboveThreshold < 240 || minutesAboveMinBG < 60) {
            consoleError.add("BG projected to remain above ${convert_bg(threshold)} for $minutesAboveThreshold minutes")
        }
        // include at least minutesAboveThreshold worth of zero temps in calculating carbsReq
        // always include at least 30m worth of zero temp (carbs to 80, low temp up to target)
        val zeroTempDuration = minutesAboveThreshold
        // BG undershoot, minus effect of zero temps until hitting min_bg, converted to grams, minus COB
        val zeroTempEffectDouble = profile.current_basal * sens * zeroTempDuration / 60
        // don't count the last 25% of COB against carbsReq
        val COBforCarbsReq = max(0.0, meal_data.mealCOB - 0.25 * meal_data.carbs)
        val carbsReq = round(((bgUndershoot - zeroTempEffectDouble) / csf - COBforCarbsReq))
        val zeroTempEffect = round(zeroTempEffectDouble)
        consoleError.add("naive_eventualBG: $naive_eventualBG bgUndershoot: $bgUndershoot zeroTempDuration $zeroTempDuration zeroTempEffect: $zeroTempEffect carbsReq: $carbsReq")
        if (carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        // don't low glucose suspend if IOB is already super negative and BG is rising faster than predicted
        if (bg < threshold && iob_data.iob < -profile.current_basal * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${iob_data.iob} < ${round(-profile.current_basal * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg(minDelta)} > expectedDelta ${convert_bg(expectedDelta)}; ")
            // predictive low glucose suspend mode: BG is / is projected to be < threshold
        } else if (bg < threshold || minGuardBG < threshold) {
            rT.reason.append("minGuardBG ${convert_bg(minGuardBG)} < ${convert_bg(threshold)}")
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
            durationReq = round(durationReq / 30.0) * 30
            // always set a 30-120m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        }

        // if not in LGS mode, cancel temps before the top of the hour to reduce beeping/vibration
        // console.error(profile.skip_neutral_temps, rT.deliverAt.getMinutes());
        val minutes = Instant.ofEpochMilli(rT.deliverAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime().minute
        if (profile.skip_neutral_temps && minutes >= 55) {
            rT.reason.append("; Canceling temp at " + minutes + "m past the hour. ")
            return setTempBasal(0.0, 0, profile, rT, currenttemp)
        }

        if (eventualBG < min_bg) { // if eventual BG is below target:
            rT.reason.append("Eventual BG ${convert_bg(eventualBG)} < ${convert_bg(min_bg)}")
            // if 5m or 30m avg BG is rising faster than expected delta
            if (minDelta > expectedDelta && minDelta > 0 && carbsReq == 0) {
                // if naive_eventualBG < 40, set a 30m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
                if (naive_eventualBG < 40) {
                    rT.reason.append(", naive_eventualBG < 40. ")
                    return setTempBasal(0.0, 30, profile, rT, currenttemp)
                }
                if (glucose_status.delta > minDelta) {
                    rT.reason.append(", but Delta ${convert_bg(tick.toDouble())} > expectedDelta ${convert_bg(expectedDelta)}")
                } else {
                    rT.reason.append(", but Min. Delta ${minDelta.toFixed2()} > Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }

            // calculate 30m low-temp required to get projected BG up to target
            // multiply by 2 to low-temp faster for increased hypo safety
            var insulinReq =
                if (dynIsfMode) 2 * min(0.0, (eventualBG - target_bg) / future_sens)
                else 2 * min(0.0, (eventualBG - target_bg) / sens)
            insulinReq = round(insulinReq, 2)
            // calculate naiveInsulinReq based on naive_eventualBG
            var naiveInsulinReq = min(0.0, (naive_eventualBG - target_bg) / sens)
            naiveInsulinReq = round(naiveInsulinReq, 2)
            if (minDelta < 0 && minDelta > expectedDelta) {
                // if we're barely falling, newinsulinReq should be barely negative
                val newinsulinReq = round((insulinReq * (minDelta / expectedDelta)), 2)
                //console.error("Increasing insulinReq from " + insulinReq + " to " + newinsulinReq);
                insulinReq = newinsulinReq
            }
            // rate required to deliver insulinReq less insulin over 30m:
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)

            // F4: Recovery hold also applies to temp basal increases (basal can act like an SMB).
            // If recovery-hold is active in no-meal context, do not add extra insulin via basal.
            if (sippRecoveryHoldSmbBlocked && rate > basal) {
                rT.reason.append("SIPP F4: RecoveryHold caps basal ${round(rate, 2)} -> ${round(basal, 2)}. ")
                rate = basal
                insulinReq = 0.0
            }


            // if required temp < existing temp basal
            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            // if current temp would deliver a lot (30% of basal) less than the required insulin,
            // by both normal and naive calculations, then raise the rate
            val minInsulinReq = Math.min(insulinReq, naiveInsulinReq)
            if (insulinScheduled < minInsulinReq - basal * 0.3) {
                rT.reason.append(", ${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} is a lot less than needed. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
            if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                rT.reason.append(", temp ${currenttemp.rate} ~< req ${round(rate, 2)}U/hr. ")
                return rT
            } else {
                // calculate a long enough zero temp to eventually correct back up to target
                if (rate <= 0) {
                    bgUndershoot = (target_bg - naive_eventualBG)
                    val worstCaseInsulinReq = bgUndershoot / sens
                    var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
                    if (durationReq < 0) {
                        durationReq = 0
                        // don't set a temp longer than 120 minutes
                    } else {
                        durationReq = round(durationReq / 30.0) * 30
                        durationReq = min(120, max(0, durationReq))
                    }
                    //console.error(durationReq);
                    if (durationReq > 0) {
                        rT.reason.append(", setting ${durationReq}m zero temp. ")
                        return setTempBasal(rate, durationReq, profile, rT, currenttemp)
                    }
                } else {
                    rT.reason.append(", setting ${round(rate, 2)}U/hr. ")
                }
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
        }

        // if eventual BG is above min but BG is falling faster than expected Delta
        if (minDelta < expectedDelta) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                if (glucose_status.delta < minDelta) {
                    rT.reason.append(
                        "Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Delta ${convert_bg(tick.toDouble())} < Exp. Delta ${
                            convert_bg(expectedDelta)
                        }"
                    )
                } else {
                    rT.reason.append("Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Min. Delta ${minDelta.toFixed2()} < Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }
        // eventualBG or minPredBG is below max_bg
        if (min(eventualBG, minPredBG) < max_bg) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convert_bg(eventualBG) + " >= " + convert_bg(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                return setTempBasal(basal, 30, profile, rT, currenttemp)
            }
        } else { // otherwise, calculate 30m high-temp required to get projected BG down to target
            // insulinReq is the additional insulin required to get minPredBG down to target_bg
            //console.error(minPredBG,eventualBG);
            var insulinReq =
                if (dynIsfMode) round((min(minPredBG, eventualBG) - target_bg) / future_sens, 2)
                else round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
            // if that would put us over max_iob, then reduce accordingly
            if (insulinReq > max_iob - iob_data.iob) {
                rT.reason.append("max_iob $max_iob, ")
                insulinReq = max_iob - iob_data.iob
            }

            // SIPP PEAK RESCUE SMB
            // "Unannounced Rise" Logic: If BG is high, rising, and predicted to peak high, add rescue SMB.
            // Trigger 1.1: BG high enough and above target
            val internalHighStart = 108.0 // ~6.0 mmol/L
            val internalHighConcern = 120.0 // ~6.7 mmol/L
            val internalPeakMargin = 10.0 // ~0.5 mmol/L
            val minRiseThreshold = 1.0 // mg/dL/5m
            val minHorizon = 90
            val maxHorizon = 180
            val internalFactorIOB = 0.4
            val internalFractionOfBaseline = 0.5


            // Check triggers
            if (bg > target_bg && bg > internalHighStart &&
                minDelta > minRiseThreshold &&
                !profile.exercise_mode && profile.half_basal_exercise_target == 0 // Trigger 1.5: No exercise/low target
            ) {
                // Trigger 1.3: Predicted peak above target within horizon
                val peakHorizonMinutes = max(minHorizon, min(maxHorizon, sippPeakMinutes))
                var peakBG = 0.0

                // Scan predictions up to horizon
                // Note: predBGs are 5-minute intervals. index * 5 = minutes.
                val maxIndex = peakHorizonMinutes / 5

                // Helper to scan a curve
                fun scanCurve(curve: List<Double>) {
                    if (curve.isEmpty()) return
                    val end = min(curve.lastIndex, maxIndex)
                    for (i in 0..end) {
                        if (curve[i] > peakBG) peakBG = curve[i]
                    }
                }

                scanCurve(IOBpredBGs)
                scanCurve(COBpredBGs)
                scanCurve(UAMpredBGs)
                // ZTpredBGs usually track IOB/COB but good to include if they exist and are higher?
                // Usually IOB/COB/UAM cover it. SIPP SMB uses these.

                // Check peak severity
                if (peakBG > target_bg + internalPeakMargin && peakBG > internalHighConcern) {
                    // Trigger 1.4: No low risk conflict
                    // We must ensure NO prediction curve dips below threshold (or min_bg?) within the horizon
                    // SIPP SMB uses minPredBG for safety. If minPredBG < threshold, we shouldn't be here?
                    // Actually, minPredBG is the minimum of the curves. If minPredBG < threshold, we should skip.
                    // We already have minPredBG calculated earlier.
                    // Let's check minPredBG against a safety floor.

                    // Also check if any curve dips low within the horizon specifically?
                    // minPredBG is the global minimum of the curves.
                    if (minPredBG > threshold) {
                        // 2. Extra correction calculation
                        val rawCorrectionUnits = max(0.0, (peakBG - target_bg) / sens)
                        val iobCompensation = internalFactorIOB * iob_data.iob
                        // Ensure we don't subtract negative IOB (which would add insulin) - though IOB should be positive here if we are high?
                        // If IOB is negative, iobCompensation is negative.
                        // effectivelyCorrection = raw - clamp(neg, 0, raw) = raw - 0 = raw.
                        // Wait, if IOB is negative, we might want to add MORE?
                        // The prompt says "Estimate how much of that correction is likely covered by existing IOB and subtract a portion".
                        // If IOB is negative, it's not covering anything. So subtraction should be 0.
                        // clamp(iobCompensation, 0.0, rawCorrectionUnits) handles this correctly (0 if neg).

                        val effectiveCorrection = max(0.0, rawCorrectionUnits - max(0.0, min(iobCompensation, rawCorrectionUnits)))

                        var peakRescueInsulinReq = effectiveCorrection

                        // 3. Safety Caps
                        // Cap relative to baseline insulinReq (absolute value)
                        // baselineInsulinReq is 'insulinReq' at this point.
                        val baselineCap = internalFractionOfBaseline * abs(insulinReq)

                        // If baseline is 0 (e.g. minPredBG > target but < eventualBG?), we might still want to rescue?
                        // "Let baselineInsulinReq be the insulinReq that SIPP SMB currently computes without this feature."
                        // If SIPP computes 0, then 0.5 * 0 = 0. So we wouldn't rescue if SIPP doesn't think we need ANY insulin?
                        // That seems to imply this feature only *boosts* existing SMBs.
                        // "Combine them: insulinReqTotal = baselineInsulinReq + peakRescueInsulinReq"
                        // If baseline is 0, we can't boost.
                        // But if SIPP sees rising BG, it usually calculates *some* insulinReq if eventualBG > target.
                        // If insulinReq is 0 here, it means min(minPredBG, eventualBG) <= target_bg.
                        // If minPredBG <= target, we shouldn't be adding rescue insulin anyway (Trigger 1.4 check implies we are safe, but maybe not high enough to trigger normal SMB).
                        // But Trigger 1.3 requires peakBG > target.
                        // If minPredBG is low but peak is high, we have a "dip then rise" or "rise then dip".
                        // If minPredBG > threshold, we are safe from lows.
                        // If insulinReq is small, we want to make it bigger.
                        // If insulinReq is 0, can we add?
                        // The prompt says: "Keep it bounded relative to baseline SIPP SMB... peakRescueUnits <= INTERNAL_FRACTION_OF_BASELINE * |baselineInsulinReq|"
                        // This strictly implies if baseline is 0, rescue is 0.
                        // This makes it a "Booster" only.

                        if (peakRescueInsulinReq > baselineCap) {
                            peakRescueInsulinReq = baselineCap
                        }

                        // If very small, skip
                        if (peakRescueInsulinReq < 0.05) { // epsilon
                            peakRescueInsulinReq = 0.0
                        }

                        if (peakRescueInsulinReq > 0) {
                            rT.reason.append(" PeakRescue +${round(peakRescueInsulinReq, 2)}U (Peak ${convert_bg(peakBG)}). ")
                            insulinReq += peakRescueInsulinReq
                        }
                    }
                }
            }


            // SIPP SAFETY: "The Panic Stack" Fix (Correction Bounds)
            // Bound total correction insulin (IOB + new SMB) to a multiple of theoretical need.
            val theoreticalCorrection = max(0.0, (bg - target_bg) / sens)
            val correctionBoundFactor = 1.2
            val correctionLimit = theoreticalCorrection * correctionBoundFactor
            val iobForCorrectionBound = max(0.0, iob_data.iob)

            // Check if we are already over the limit
            if (theoreticalCorrection > 0 && iobForCorrectionBound > correctionLimit) {
                rT.reason.append("SIPP Safety: IOB ${round(iob_data.iob, 2)} > Limit ${round(correctionLimit, 2)} (${correctionBoundFactor}x). SMB=0, Neutral Basal. ")
                consoleError.add("SIPP Safety: IOB > Correction Limit. SMB disabled, Basal reset to profile.")
                // Force neutral basal (profile.current_basal) and zero SMB
                // We return immediately to prevent SMB logic from running
                return setTempBasal(profile.current_basal, 30, profile, rT, currenttemp)
            }

            // rate required to deliver insulinReq more insulin over 30m:
            var rate = basal + (2 * insulinReq)

            // SIPP SAFETY: Sleep Band Basal Cap
            if (isSleepBand) {
                val sleepBasalCap = profile.current_basal * 1.1
                if (rate > sleepBasalCap) {
                    rT.reason.append("SIPP Sleep: Cap basal ${round(rate, 2)} -> ${round(sleepBasalCap, 2)}. ")
                    rate = sleepBasalCap
                    // Recalculate insulinReq based on capped rate to keep things consistent downstream
                    // rate = basal + 2 * insulinReq  =>  insulinReq = (rate - basal) / 2
                    insulinReq = max(0.0, (rate - basal) / 2)
                }
            }

            rate = round_basal(rate)
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq

            // SIPP SAFETY: Sleep Band SMB Disable
            if (isSleepBand && enableSMB) {
                rT.reason.append("SIPP Sleep: SMB disabled in safe band. ")
                enableSMB = false
            }

            // SIPP SAFETY: Enforce Correction Bound on new SMB
            // If adding insulinReq (approx SMB size) would push us over, clamp it.
            // Note: Actual SMB size is calculated below as microBolus, but we clamp insulinReq here to influence it.
            if (theoreticalCorrection > 0 && iobForCorrectionBound + insulinReq > correctionLimit) {
                val maxAllowed = max(0.0, correctionLimit - iobForCorrectionBound)
                if (insulinReq > maxAllowed) {
                    rT.reason.append("SIPP Safety: Clamping req ${round(insulinReq, 2)} -> ${round(maxAllowed, 2)} to fit limit. ")
                    insulinReq = round(maxAllowed, 3)
                    rT.insulinReq = insulinReq
                    // Recalculate rate based on clamped insulinReq
                    rate = round_basal(basal + (2 * insulinReq))
                }
            }
            //console.error(iob_data.lastBolusTime);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus
            // NOTE: Do not gate SMB on rT.rate here: rT is the output object and its rate may be null at this point,
            // which would incorrectly disable SMB entirely.
            if (microBolusAllowed && enableSMB && bg > threshold && bg >= target_bg && !sippSmbBlocked) {
                // SIPP SMB caps are absolute insulin units (U), not "basal minutes".
                val mealInsulinReq = round(meal_data.mealCOB / profile.carb_ratio, 3)
                val iobU = if (iob_data.iob.isFinite()) iob_data.iob else 0.0
                val correctionIOBU = max(0.0, iobU - max(0.0, mealInsulinReq))

                // Demand (U) from ACTIVE prediction trace peak using SIPP-first ISF (peakScanResult),
                // falling back to insulinReq if peak demand is unavailable/invalid.
                val demandU = peakScanResult?.requiredAdditionalInsulinU
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: max(0.0, insulinReq)

                // Remaining demand after subtracting correction-relevant IOB (IOB above meal coverage).
                val remainingDemandU = max(0.0, demandU - correctionIOBU)

                // Choose which user cap applies (normal SMB vs UAM SMB).
                val isUamContext = (peakScanResult?.activeTraceType == "UAM") || (iobU > mealInsulinReq && iobU > 0)
                val prefCapU = if (isUamContext) sippMaxUamSmbBolusU else sippMaxSmbBolusU
                val maxBolus = prefCapU.coerceIn(0.0, 25.0)

                // bolus fraction of insulinReq, up to cap and remaining demand, rounding down to nearest bolus increment
                val roundSMBTo = 1 / profile.bolus_increment

                // ========================= F2: Two-sided constrained dosing =========================
                // u_high  : insulin needed to prevent exceeding max_bg at the fast-window peak
                // u_lowMax: maximum insulin allowed before any predicted point in low-horizon would go below the low floor
                // clamp(0, u_high, u_lowMax) enforces: never negative, prevent highs as much as possible, never at the cost of predicted lows.
                //
                // IMPORTANT: use a robust ISF for constraints (SIPP instant ISF > dynISF > profile sens) and never assume peakScanResult is non-null.
                val constraintIsf = when {
                    sippInstantIsfMode && sippInstantIsfMgdlPerU > 0 -> sippInstantIsfMgdlPerU
                    dynIsfMode && future_sens > 0                    -> future_sens
                    else                                             -> sens
                }

                val endIdxHighF2 = min(activePredictions.lastIndex, fastWindowMinutes / 5)
                val predHighBg = peakScanResult?.peakPredBg
                    ?: (activePredictions.subList(0, endIdxHighF2 + 1).maxOrNull() ?: bg)
                val u_high = if (constraintIsf > 0) max(0.0, (predHighBg - max_bg) / constraintIsf) else 0.0

                val lowFloor = max(threshold, min_bg)
                val endIdxLowF2 = min(activePredictions.lastIndex, sippGuardLowMinutes / 5)
                val predLowBg = peakScanResult?.minPredBgLow
                    ?: (activePredictions.subList(0, endIdxLowF2 + 1).minOrNull() ?: bg)
                val u_lowMax = if (constraintIsf > 0) max(0.0, (predLowBg - lowFloor) / constraintIsf) else 0.0

                // Correction-bound headroom (existing safety concept): don't exceed the correction band once correction IOB is accounted for.
                val correctionLimit = if (constraintIsf > 0) max(0.0, (max_bg - target_bg) / constraintIsf) else 0.0
                val iobForCorrectionBound = max(0.0, correctionIOBU)
                val correctionHeadroom = max(0.0, correctionLimit - iobForCorrectionBound)

                // Constrained request:
                val constrainedU = min(u_high, u_lowMax)
                val requestedU = min(constrainedU, min(maxBolus, remainingDemandU + correctionHeadroom))

                val allowedU = max(0.0, requestedU)
                var microBolus = Math.floor(allowedU * roundSMBTo) / roundSMBTo

                consoleError.add(
                    "SIPP SMB cap: ctx=${if (isUamContext) "UAM" else "SMB"} capU=${round(maxBolus, 2)}U, " +
                        "demandU=${round(demandU, 2)}U, correctionIOB=${round(correctionIOBU, 2)}U, remainingDemandU=${round(remainingDemandU, 2)}U, " +
                        "insulinReq=${round(insulinReq, 2)}U => microBolus=${round(microBolus, 2)}U"
                )

                // calculate a long enough zero temp to eventually correct back up to target
                // For SMB "limit" style calculations, use target_low (min_bg) as requested
                val smbTarget = min_bg
                val worstCaseInsulinReq = (smbTarget - (naive_eventualBG + minIOBPredBG) / 2.0) / sens
                var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)

                // if allowedU > 0 but not enough for a microBolus, don't set an SMB zero temp
                if (allowedU > 0 && microBolus < profile.bolus_increment) {
                    durationReq = 0
                }

                var smbLowTempReq = 0.0
                if (durationReq <= 0) {
                    durationReq = 0
                    // don't set an SMB zero temp longer than 60 minutes
                } else if (durationReq >= 30) {
                    durationReq = round(durationReq / 30.0) * 30
                    durationReq = min(60, max(0, durationReq))
                } else {
                    // if SMB durationReq is less than 30m, set a nonzero low temp
                    smbLowTempReq = round(basal * durationReq / 30.0, 2)
                    durationReq = 30
                }
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus $maxBolus")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${smbLowTempReq}U/h")
                }
                rT.reason.append(". ")

                // seconds since last bolus
                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0
                //console.error(lastBolusAge);
                // allow SMBIntervals between 1 and 10 minutes
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0   // in seconds
                //console.error(naive_eventualBG, insulinReq, worstCaseInsulinReq, durationReq);
                consoleError.add("naive_eventualBG $naive_eventualBG,${durationReq}m ${smbLowTempReq}U/h temp needed; last bolus ${round(lastBolusAge / 60.0, 1)}m ago; maxBolus: $maxBolus")
                if (lastBolusAge > SMBInterval - 6.0) {   // 6s tolerance
                    if (microBolus > 0) {
                        // Final defensive clamp: absolute invariant before assignment
                        microBolus = min(microBolus, maxBolus)
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                } else {
                    val nextBolusMins = (SMBInterval - lastBolusAge) / 60.0
                    val nextBolusSeconds = (SMBInterval - lastBolusAge) % 60
                    val waitingSeconds = round(nextBolusSeconds, 0) % 60
                    val waitingMins = round(nextBolusMins - waitingSeconds / 60.0, 0)
                    rT.reason.append("Waiting ${waitingMins.withoutZeros()}m ${waitingSeconds.withoutZeros()}s to microbolus again.")
                }
                //rT.reason += ". ";

                // if no zero temp is required, don't return yet; allow later code to set a high temp
                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }

            }

            val maxSafeBasal = getMaxSafeBasal(profile)

            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${round(rate, 2)} to maxSafeBasal: ${maxSafeBasal.withoutZeros()}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            if (insulinScheduled >= insulinReq * 2) { // if current temp would deliver >2x more than the required insulin, lower the rate
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} > 2 * insulinReq. Setting temp basal of ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) { // no temp is set
                rT.reason.append("no temp, setting " + round(rate, 2).withoutZeros() + "U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 && (round_basal(rate) <= round_basal(currenttemp.rate))) { // if required temp <~ existing temp basal
                rT.reason.append("temp ${(currenttemp.rate).toFixed2()} >~ req ${round(rate, 2).withoutZeros()}U/hr. ")
                return rT
            }

            // required temp > existing temp basal
            rT.reason.append("temp ${currenttemp.rate.toFixed2()} < ${round(rate, 2).withoutZeros()}U/hr. ")
            return setTempBasal(rate, 30, profile, rT, currenttemp)
        }
    }
}
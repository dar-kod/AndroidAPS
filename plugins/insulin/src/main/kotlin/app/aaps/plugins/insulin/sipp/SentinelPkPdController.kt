package app.aaps.plugins.insulin.sipp

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SIPP = Sentinel Instant PK/PD Controller
 *
 * - Never shortens DIA due to residual/slope (safer for slow sites/stacking).
 * - Persists per site; seeds from Profile (not 10 h) on first tick.
 * - Safety rails + decay to profile; no tightening while falling BG, neg IOB, or suspend.
 * - Exposes diagnostics so you can verify accuracy (RMSE/Bias/Confidence).
 *
 * Call applyEvidence() once per loop; read current() from your insulin plugin.
 */
@Singleton
class SentinelPkPdController @Inject constructor() {

    // ---------------- Public API ----------------

    data class Estimates(
        val diaH: Float,     // hours
        val peakH: Float?,   // hours (nullable => use profile peak)
        val isfScale: Float  // ×1.00 = no change
    )

    data class Diagnostics(
        val rmse: Float,     // same units as CGM (mg/dL typically)
        val bias: Float,     // mean residual; negative => insulin stronger than model
        val confidence: Float // 0..1 (higher => more trustworthy)
    )

    fun current(): Estimates = synchronized(this) {
        Estimates(diaH = diaH, peakH = tPeakMin / 60f, isfScale = isfMult)
    }

    @Suppress("unused")
    fun diagnostics(): Diagnostics = synchronized(this) {
        Diagnostics(
            rmse = calcRmse(),
            bias = if (count > 0) sumResidual / count else 0f,
            confidence = calcConfidence()
        )
    }

    /**
     * O(1) evidence update. Call once per loop, before dose calc.
     */
    fun applyEvidence(
        nowMs: Long,
        bgNow: Double,
        bgPred: Double,
        deltaPerMin: Double,
        iobU: Double,
        basalSuspendedMin: Int,
        siteAgeHours: Double,
        minutesSinceLastBolus: Int,
        hr: Int?,
        inExercise: Boolean?,
        sensorOk: Boolean,
        baseDiaH: Float,
        basePeakMin: Int
    ) = synchronized(this) {

        val hadSaved = tryRestoreOnce()
        if (!hadSaved && !seededFromProfile) {
            val ceil = if (SippPrefs.allowDiaAbove9h()) DIA_MAX else 12.0f
            diaH = baseDiaH.coerceIn(DIA_MIN, ceil)
            tPeakMin = basePeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
            isfMult = 1.00f
            seededFromProfile = true
        }

        val dtMin = if (lastTickMs == 0L) 5.0f else max(1L, nowMs - lastTickMs) / 60000.0f
        lastTickMs = nowMs

        if (!SippPrefs.enablePk() || !sensorOk) {
            decayToward(baseDiaH, basePeakMin, 1.0f, dtMin)
            persist(nowMs)
            return
        }

        val residual = (bgNow - bgPred).toFloat() // negative => insulin stronger
        pushTelem(residual)

        // Sentinels
        val earlySite = siteAgeHours < 24.0
        val stack = minutesSinceLastBolus in 1..120
        val falling = deltaPerMin < -0.05
        val prolongedSuspend = basalSuspendedMin >= 20
        val negIob = iobU < 0.0

        val slowSiteScore =
            (if (earlySite) 1 else 0) +
                (if (stack) 1 else 0) +
                (if (falling && (negIob || prolongedSuspend)) 1 else 0) +
                (if (biasStrong()) 1 else 0)
        val slowSiteActive = slowSiteScore >= 2

        // Activity → later peak & slightly weaker ISF
        val exercise = (inExercise == true) || ((hr ?: 0) >= 100)
        val hrBias = when {
            (hr ?: 0) >= 140 -> 0.10f
            (hr ?: 0) >= 120 -> 0.06f
            (hr ?: 0) >= 100 -> 0.03f
            else             -> 0.0f
        }
        val targetIsfMult = 1.0f * (1.0f + hrBias)
        val targetPeakMin = (if (exercise) basePeakMin + 10 else basePeakMin)
            .coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)

        // NEVER shorten DIA from residual/slope logic
        val strongInsulin = residual < -RESIDUAL_TH_STRONG || (falling && (negIob || prolongedSuspend))
        val weakInsulin = residual > RESIDUAL_TH_STRONG && !falling && !negIob && !prolongedSuspend

        if (strongInsulin) {
            // insulin stronger → lengthen horizon, later peak, weaker ISF
            diaH += DIA_STEP_UP
            tPeakMin += TPEAK_STEP_MIN
            isfMult += ISF_STEP_WEAKEN
        } else if (weakInsulin) {
            // insulin weaker → DO NOT shorten DIA; adjust ISF/peak gently
            isfMult -= ISF_STEP_STRENGTHEN
            tPeakMin -= TPEAK_STEP_MIN
        } else {
            // quiet → slow decay toward profile & activity targets
            decayToward(baseDiaH, targetPeakMin, targetIsfMult, dtMin)
        }

        // Floors & rails (slow-site floors)
        val diaCeil = if (SippPrefs.allowDiaAbove9h()) DIA_MAX else 12.0f
        val floor = when {
            slowSiteActive && falling -> max(DIA_FLOOR_HARD, baseDiaH)
            slowSiteActive            -> max(DIA_FLOOR_SOFT, baseDiaH)
            else                      -> max(DIA_MIN, baseDiaH)
        }
        if (diaH < floor) diaH = (diaH + floor) / 2.0f
        diaH = diaH.coerceIn(DIA_MIN, min(diaCeil, DIA_MAX))
        tPeakMin = tPeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
        isfMult = isfMult.coerceIn(ISF_MIN, ISF_MAX)

        persist(nowMs)
    }

    // ---------------- internals ----------------

    // Rails (ALL_CAPS constants are conventional in this codebase)
    private val DIA_MIN = 5.0f
    private val DIA_MAX = 20.0f
    private val TPEAK_MIN_MIN = 50
    private val TPEAK_MAX_MIN = 180
    private val ISF_MIN = 0.60f
    private val ISF_MAX = 1.40f

    // Steps / decay
    private val DIA_STEP_UP = 0.25f
    private val TPEAK_STEP_MIN = 3
    private val ISF_STEP_WEAKEN = 0.02f
    private val ISF_STEP_STRENGTHEN = 0.02f
    private val DECAY_PER_H = 0.08f

    // Slow-site aids
    private val DIA_FLOOR_SOFT = 9.0f
    private val DIA_FLOOR_HARD = 14.0f

    // Residual telemetry
    private val RESIDUAL_TH_STRONG = 6.0f
    private val CUSUM_DECAY = 0.85f

    // State
    @Volatile private var diaH: Float = 10.0f
    @Volatile private var tPeakMin: Int = 133
    @Volatile private var isfMult: Float = 1.00f
    @Volatile private var lastTickMs: Long = 0L
    @Volatile private var restored = false
    @Volatile private var seededFromProfile = false

    // Telemetry accumulators
    private var sumSq: Float = 0f
    private var sumResidual: Float = 0f
    private var count: Int = 0
    private var cusum: Float = 0f

    private fun tryRestoreOnce(): Boolean {
        if (restored) return true
        restored = true
        SippPrefs.loadState()?.let { s ->
            diaH = s.diaH.coerceIn(DIA_MIN, DIA_MAX)
            tPeakMin = s.tPeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
            isfMult = s.isfMult.coerceIn(ISF_MIN, ISF_MAX)
            return true
        }
        return false
    }

    private fun persist(nowMs: Long) {
        SippPrefs.saveState(diaH, tPeakMin, isfMult, nowMs)
    }

    private fun decayToward(baseDiaH: Float, targetPeakMin: Int, targetIsfMult: Float, dtMin: Float) {
        val k = DECAY_PER_H * (dtMin / 60.0f)
        // NEVER shorten DIA below base here; bias toward longer horizon
        val diaTarget = max(baseDiaH, diaH)
        diaH += (diaTarget - diaH) * k
        tPeakMin += ((targetPeakMin - tPeakMin).toFloat() * k).toInt()
        isfMult += (targetIsfMult - isfMult) * k
    }

    private fun pushTelem(residual: Float) {
        sumSq += residual * residual
        sumResidual += residual
        count += 1
        cusum = cusum * CUSUM_DECAY + residual
    }

    private fun biasStrong(): Boolean = cusum < -20f

    private fun calcRmse(): Float = if (count > 0) sqrt(sumSq / count) else 0f

    private fun calcConfidence(): Float {
        val rmse = calcRmse()
        val b = if (count > 0) sumResidual / count else 0f
        val rmseScore = (1f - (rmse / 30f)).coerceIn(0f, 1f)    // 30 mg/dL soft bound
        val biasScore = (1f - (abs(b) / 15f)).coerceIn(0f, 1f)  // 15 mg/dL soft bound
        return (0.6f * rmseScore + 0.4f * biasScore)
    }
}

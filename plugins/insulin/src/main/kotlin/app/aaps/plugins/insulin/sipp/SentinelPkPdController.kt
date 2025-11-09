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
 * - Never shortens DIA from residual/slope alone (safer for slow sites/stacking).
 * - Persists per site; seeds from Profile on first tick if nothing saved.
 * - Safety rails + gentle decay to profile; no tightening while falling BG, neg IOB, or suspend.
 * - Exposes diagnostics so you can verify accuracy (RMSE/Bias/Confidence).
 * - Optional activity fusion (cadence + HR) via onStep()/setHr()/setExerciseFlag().
 *
 * Call applyEvidence() once per loop; read current() from your insulin plugin.
 * Feed steps/HR when available; otherwise SIPP falls back gracefully.
 */
@Singleton
class SentinelPkPdController @Inject constructor() {

    // ---------------- Public API ----------------

    data class Estimates(
        val diaH: Float,     // hours
        val peakH: Float?,   // hours (nullable => use profile peak if you prefer)
        val isfScale: Float  // ×1.00 = no change
    )

    data class Diagnostics(
        val rmse: Float,      // same units as CGM (mg/dL typically)
        val bias: Float,      // mean residual; negative => insulin stronger than model
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

    // --- Optional activity inputs (thread-safe) ---

    /** Feed heart rate (bpm) whenever you have it. Pass null to clear. */
    fun setHr(bpm: Int?) = synchronized(this) {
        hrLatest = bpm?.coerceIn(30, 220)
    }

    /** Optional user/app hint for exercise state; SIPP will fuse with cadence/HR. */
    fun setExerciseFlag(flag: Boolean?) = synchronized(this) {
        exerciseHint = flag
    }

    /** Feed raw step events (usually count=1 per pedometer tick). */
    @Suppress("unused")
    fun onStep(count: Int, tsMs: Long) = synchronized(this) {
        if (count <= 0) return
        initCadenceIfNeeded()
        rollCadence(tsMs)
        val idx = ((tsMs / 60000L) % CADENCE_BUCKETS.toLong()).toInt()
        stepBuckets[idx] = (stepBuckets[idx] + count).coerceAtLeast(0)
        lastCadenceTs = tsMs
        recomputeCadenceDerivedLocked()
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

        // One-time restore from persisted state.
        val restoredNow = tryRestoreOnce()

        // If nothing restored and not seeded yet, seed from current profile.
        if (!restoredNow && !seededFromProfile) {
            val diaCeil = if (SippPrefs.allowDiaAbove9h()) DIA_MAX else 9.0f
            diaH = baseDiaH.coerceIn(DIA_MIN, diaCeil)
            tPeakMin = basePeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
            isfMult = 1.00f
            seededFromProfile = true
        }

        // dt (minutes) for smooth decay/tweening
        val dtMin = if (lastTickMs == 0L) 5.0f else max(1L, nowMs - lastTickMs) / 60000.0f
        lockoutMin = (lockoutMin - dtMin).coerceAtLeast(0f)
        lastTickMs = nowMs

        // If PK is disabled or sensor is bad → gently drift toward profile targets and persist.
        if (!SippPrefs.enablePk() || !sensorOk) {
            decayToward(baseDiaH, basePeakMin, 1.0f, dtMin)
            persist(nowMs)
            return
        }

        // Fuse external hints with internal activity signals and keep cadence rolling
        setHr(hr)
        setExerciseFlag(inExercise)
        if (cadenceInitialized) {
            rollCadence(nowMs)
            recomputeCadenceDerivedLocked()
        }

        // Residual = actual - predicted (negative => insulin stronger than model)
        val residual = (bgNow - bgPred).toFloat()
        val absResidual = abs(residual)

        // --- Spike rejection: ignore single noisy ticks for learning ---
        val steepSlope = abs(deltaPerMin).toFloat() > SPIKE_SLOPE_TH
        val bigJump = absResidual > SPIKE_RESIDUAL_TH
        val recentlyUpdated = lastResidualTs != 0L &&
            (nowMs - lastResidualTs) <= SPIKE_WINDOW_MIN * 60_000L
        val signFlip = lastResidual * residual < 0f

        val isSpike = steepSlope && (bigJump || (recentlyUpdated && signFlip))
        if (isSpike) {
            pushTelem(residual)
            quietMin = 0f
            decayToward(baseDiaH, basePeakMin, 1.0f, dtMin)
            lastResidual = residual
            lastResidualTs = nowMs
            persist(nowMs)
            return
        }

        // Normal telemetry path
        pushTelem(residual)

        // Situational sentinels
        val earlySite = siteAgeHours < 24.0
        val stack = minutesSinceLastBolus in 1..120
        val falling = deltaPerMin < -0.05
        val prolongedSuspend = basalSuspendedMin >= 20
        val negIob = iobU < 0.0

        // Late-low penalty: lows in the late tail are strong evidence for longer DIA.
        val bolusAgeMin = minutesSinceLastBolus.toFloat()
        val diaTailStartMin = baseDiaH * 60f * LATE_TAIL_START_FRAC
        val diaTailEndMin = baseDiaH * 60f * LATE_TAIL_END_FRAC
        val inLateTail = bolusAgeMin in diaTailStartMin..diaTailEndMin
        val lateLow = inLateTail && residual < -RESIDUAL_TH_STRONG

        val slowSiteScore =
            (if (earlySite) 1 else 0) +
                (if (stack) 1 else 0) +
                (if (falling && (negIob || prolongedSuspend)) 1 else 0) +
                (if (biasStrong()) 1 else 0) +
                (if (lateLow) 1 else 0)
        val slowSiteActive = slowSiteScore >= 2

        // -------- Activity fusion (cadence + HR + hint) --------
        val spm = stepsPerMin
        val sustainedActiveMin = activityWindowMin
        val hrBpm = hrLatest

        val cadenceActive = (spm != null && spm >= CADENCE_ACTIVE_SPM_MIN && sustainedActiveMin >= CADENCE_SUSTAIN_MIN)
        val hrActive = (hrBpm != null && hrBpm >= HR_ACTIVE_MIN)
        val hintActive = (exerciseHint == true)
        val exercising = cadenceActive || hrActive || hintActive

        // HR-scaled ISF bias (small, conservative)
        val hrBias = when {
            hrBpm != null && hrBpm >= 140 -> 0.10f
            hrBpm != null && hrBpm >= 120 -> 0.06f
            hrBpm != null && hrBpm >= 100 -> 0.03f
            else                          -> 0.0f
        }
        // Cadence adds a little more, capped
        val cadenceBias = if (cadenceActive) 0.02f else 0.0f
        val targetIsfMult = 1.0f * (1.0f + (hrBias + cadenceBias).coerceAtMost(0.12f))

        // Peak shift: during sustained activity, nudge later by up to +10 min (redistribution)
        val peakShift = if (exercising) 10 else 0
        val targetPeakMin = (basePeakMin + peakShift).coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
        // -------------------------------------------------------

        // NEVER shorten DIA from residual/slope logic
        val strongInsulin = residual < -RESIDUAL_TH_STRONG || (falling && (negIob || prolongedSuspend))
        val weakInsulin = residual > RESIDUAL_TH_STRONG && !falling && !negIob && !prolongedSuspend

        if (strongInsulin) {
            var stepScale = computeStepScale(absResidual)
            if (lateLow) stepScale = max(stepScale, LATE_LOW_MIN_STEP_SCALE)
            diaH += DIA_STEP_UP * stepScale
            val peakStep = max(1, (TPEAK_STEP_MIN * stepScale).toInt())
            tPeakMin += peakStep
            isfMult += ISF_STEP_WEAKEN * stepScale
            quietMin = 0f
            lockoutMin = STRONG_LOCKOUT_MIN
        } else if (weakInsulin) {
            val stepScale = computeStepScale(absResidual)
            val peakStep = max(1, (TPEAK_STEP_MIN * stepScale).toInt())
            isfMult -= ISF_STEP_STRENGTHEN * stepScale
            tPeakMin -= peakStep
            quietMin += dtMin
        } else {
            if (!falling && !negIob && !prolongedSuspend) {
                quietMin += dtMin
            }
            decayToward(baseDiaH, targetPeakMin, targetIsfMult, dtMin)
        }

        // Floors & rails (slow-site + late-low floors)
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

        lastResidual = residual
        lastResidualTs = nowMs

        persist(nowMs)
    }

    // ---------------- internals ----------------

    companion object {

        // --- Relaxation gate (allows DIA to come down safely) ---
        private const val RELAX_UNLOCK_CONF: Float = 0.75f
        private const val RELAX_AFTER_MIN: Float = 60f
        private const val RELAX_RATE_PER_H: Float = 0.03f
        private const val STRONG_LOCKOUT_MIN: Float = 45f

        // Rails
        private const val DIA_MIN: Float = 4.5f
        private const val DIA_MAX: Float = 24.0f
        private const val TPEAK_MIN_MIN: Int = 45
        private const val TPEAK_MAX_MIN: Int = 210
        private const val ISF_MIN: Float = 0.60f
        private const val ISF_MAX: Float = 1.50f

        // Steps / decay
        private const val DIA_STEP_UP: Float = 0.25f
        private const val TPEAK_STEP_MIN: Int = 3
        private const val ISF_STEP_WEAKEN: Float = 0.02f
        private const val ISF_STEP_STRENGTHEN: Float = 0.02f
        private const val DECAY_PER_H: Float = 0.08f

        // Slow-site aids
        private const val DIA_FLOOR_SOFT: Float = 9.0f
        private const val DIA_FLOOR_HARD: Float = 14.0f

        // Residual telemetry
        private const val RESIDUAL_TH_STRONG: Float = 6.0f
        private const val CUSUM_DECAY: Float = 0.85f

        // Spike rejection
        private const val SPIKE_SLOPE_TH: Float = 3.0f
        private const val SPIKE_RESIDUAL_TH: Float = 20.0f
        private const val SPIKE_WINDOW_MIN: Long = 15L

        // Late-low handling (tail of DIA)
        private const val LATE_TAIL_START_FRAC: Float = 0.6f
        private const val LATE_TAIL_END_FRAC: Float = 1.5f
        private const val LATE_LOW_MIN_STEP_SCALE: Float = 0.7f

        // Cadence detector params
        private const val CADENCE_BUCKETS = 10                 // 10×1-min buckets (rolling 10 min)
        private const val CADENCE_ACTIVE_SPM_MIN = 60          // ≥60 steps/min considered active
        private const val CADENCE_SUSTAIN_MIN = 5              // need ≥5 consecutive active minutes
        private const val HR_ACTIVE_MIN = 100                  // HR heuristic for activity
    }

    // State (timers & dynamics)
    @Volatile private var quietMin: Float = 0f
    @Volatile private var lockoutMin: Float = 0f

    @Volatile private var diaH: Float = 9.0f
    @Volatile private var tPeakMin: Int = 120
    @Volatile private var isfMult: Float = 1.00f
    @Volatile private var lastTickMs: Long = 0L
    @Volatile private var restored: Boolean = false
    @Volatile private var seededFromProfile: Boolean = false

    // Telemetry accumulators
    private var sumSq: Float = 0f
    private var sumResidual: Float = 0f
    private var count: Int = 0
    private var cusum: Float = 0f

    // For spike detection / temporal context
    private var lastResidual: Float = 0f
    private var lastResidualTs: Long = 0L

    // Activity/cadence state
    private var hrLatest: Int? = null
    private var exerciseHint: Boolean? = null

    private var cadenceInitialized = false
    private var stepBuckets = IntArray(CADENCE_BUCKETS)       // zero-initialized
    private var lastCadenceTs: Long = 0L
    private var stepsPerMin: Int? = null
    private var activityWindowMin: Int = 0

    private fun initCadenceIfNeeded() {
        if (!cadenceInitialized) {
            cadenceInitialized = true
            lastCadenceTs = System.currentTimeMillis()
            stepBuckets.fill(0)
            stepsPerMin = null
            activityWindowMin = 0
        }
    }

    /** Advance ring buffer up to current minute, zeroing skipped minutes and updating sustained count. */
    private fun rollCadence(tsMs: Long) {
        if (!cadenceInitialized) return
        val lastMin = lastCadenceTs / 60000L
        val curMin = tsMs / 60000L
        var m = lastMin
        while (m < curMin) {
            m++
            val idx = (m % CADENCE_BUCKETS).toInt()
            val endedIdx = ((m - 1) % CADENCE_BUCKETS).toInt()
            val endedSpm = stepBuckets[endedIdx]
            activityWindowMin = if (endedSpm >= CADENCE_ACTIVE_SPM_MIN) {
                (activityWindowMin + 1).coerceAtMost(CADENCE_BUCKETS)
            } else 0
            stepBuckets[idx] = 0
        }
        lastCadenceTs = tsMs
    }

    /** Recompute SPM over the most recent complete minute. */
    private fun recomputeCadenceDerivedLocked() {
        val curMin = (lastCadenceTs / 60000L)
        val endedIdx = ((curMin - 1).coerceAtLeast(0) % CADENCE_BUCKETS).toInt()
        val spm = if (lastCadenceTs == 0L) 0 else stepBuckets[endedIdx]
        stepsPerMin = if (spm > 0) spm else null
    }

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

        // Gate to allow shortening DIA only when clearly safe
        val allowRelax =
            lockoutMin <= 0f &&
                calcConfidence() >= RELAX_UNLOCK_CONF &&
                quietMin >= RELAX_AFTER_MIN

        val diaTarget = if (allowRelax) baseDiaH else max(baseDiaH, diaH)

        val nextDia = diaH + (diaTarget - diaH) * k
        val maxDownStep = (RELAX_RATE_PER_H * (dtMin / 60.0f))
        diaH = if (allowRelax && nextDia < diaH) {
            max(nextDia, diaH - maxDownStep)
        } else {
            nextDia
        }

        tPeakMin += ((targetPeakMin - tPeakMin).toFloat() * k).toInt()
        isfMult += (targetIsfMult - isfMult) * k
    }

    private fun computeStepScale(absResidual: Float): Float {
        val raw = absResidual / (RESIDUAL_TH_STRONG * 2f)
        return raw.coerceIn(0.3f, 1.0f)
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

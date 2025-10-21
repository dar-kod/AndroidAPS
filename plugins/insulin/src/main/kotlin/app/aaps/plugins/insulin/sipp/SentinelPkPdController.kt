package app.aaps.plugins.insulin.sipp

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * SIPP = Sentinel Instant PK/PD Controller
 *
 * Always-on by default (no knobs). Battery-neutral (O(1) per loop).
 * Uses universal evidence (BG residuals, slope, IOB, suspensions, site timing, HR/exercise)
 * with hard rails and decay toward baseline.
 *
 * Call [applyEvidence] once per loop BEFORE dose calculation,
 * then read [current] for DIA (h), peak time (h), and ISF scale (×).
 */
@Singleton
class SentinelPkPdController @Inject constructor() {

    data class Estimates(
        val diaH: Float,        // hours
        val peakH: Float?,      // hours (nullable => use profile)
        val isfScale: Float     // 1.00 = no change
    )

    // --- Rails ---
    private val DIA_MIN = 5.0f
    private val DIA_MAX = 20.0f
    private val TPEAK_MIN_MIN = 50
    private val TPEAK_MAX_MIN = 180
    private val ISF_MIN = 0.60f
    private val ISF_MAX = 1.40f

    // --- Step sizes & decay ---
    private val DIA_STEP = 0.20f
    private val TPEAK_STEP_MIN = 3
    private val ISF_STEP = 0.02f
    private val DECAY_PER_H = 0.10f

    // --- Residual tracker (CUSUM) ---
    private val CUSUM_MIN = -30.0f
    private val CUSUM_MAX = 30.0f
    private val CUSUM_DECAY = 0.85f

    // --- Slow-site heuristics ---
    private val DIA_FLOOR_SOFT = 9.0f
    private val DIA_FLOOR_HARD = 14.0f

    // --- State ---
    @Volatile private var diaH: Float = 10.0f
    @Volatile private var tPeakMin: Int = 133
    @Volatile private var isfMult: Float = 1.00f
    @Volatile private var cusum: Float = 0.0f
    @Volatile private var lastTickMs: Long = 0L
    @Volatile private var restored = false

    private fun tryRestoreOnce() {
        if (restored) return
        restored = true
        // If there is a saved live state, restore it so installs/updates don't reset DIA/Peak/ISF.
        SippPrefs.loadState()?.let { s ->
            diaH = s.diaH.coerceIn(DIA_MIN, DIA_MAX)
            tPeakMin = s.tPeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
            isfMult = s.isfMult.coerceIn(ISF_MIN, ISF_MAX)
        }
    }

    fun current(): Estimates = synchronized(this) {
        tryRestoreOnce()
        Estimates(diaH = diaH, peakH = tPeakMin / 60f, isfScale = isfMult)
    }

    /**
     * Evidence update (call once per loop).
     *
     * All units consistent with your app (mg/dL or mmol/L); just be consistent.
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
        tryRestoreOnce()

        val dtMin = if (lastTickMs == 0L) 5.0f else max(1L, nowMs - lastTickMs) / 60000.0f
        lastTickMs = nowMs

        // If PK disabled → soft decay and exit
        if (!SippPrefs.enablePk()) {
            decay(dtMin, baseDiaH, basePeakMin)
            SippPrefs.saveState(diaH, tPeakMin, isfMult, nowMs)
            return
        }

        // If sensor bad → freeze params but decay toward baseline
        if (!sensorOk) {
            decay(dtMin, baseDiaH, basePeakMin)
            clampAll()
            SippPrefs.saveState(diaH, tPeakMin, isfMult, nowMs)
            return
        }

        // 1) Residual (negative => insulin stronger than model)
        val residual = (bgNow - bgPred).toFloat()
        cusum = (cusum * CUSUM_DECAY) + residual * dtMin
        cusum = cusum.coerceIn(CUSUM_MIN, CUSUM_MAX)

        // 2) Sentinels
        val earlySite = siteAgeHours < 24.0
        val recentStack = minutesSinceLastBolus in 1..120
        val falling = deltaPerMin < -0.05   // adjust if mmol/L
        val prolongedSuspend = basalSuspendedMin >= 20
        val negIob = iobU < 0.0

        val slowSiteScore =
            (if (earlySite) 1 else 0) +
                (if (recentStack) 1 else 0) +
                (if (falling && (negIob || prolongedSuspend)) 1 else 0) +
                (if (cusum < -10.0f) 1 else 0)
        val slowSiteActive = slowSiteScore >= 2

        // 3) Activity bias (optional; no-op if absent)
        val exercise = (inExercise == true) || ((hr ?: 0) >= 100)
        val hrBias = when {
            (hr ?: 0) >= 140 -> 0.10f
            (hr ?: 0) >= 120 -> 0.06f
            (hr ?: 0) >= 100 -> 0.03f
            else             -> 0.0f
        }
        val targetIsf = 1.0f * (1.0f + hrBias)
        val targetPeakMin = (if (exercise) basePeakMin + 10 else basePeakMin)
            .coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)

        // 4) Adaptive steps (sign-aware)
        if (cusum < -5.0f || (falling && negIob)) {
            // insulin too strong → lengthen DIA, push peak later, weaken ISF a bit
            diaH += DIA_STEP
            tPeakMin += TPEAK_STEP_MIN
            isfMult += ISF_STEP
        } else if (cusum > 5.0f) {
            // insulin too weak → shorten DIA, pull peak earlier, strengthen ISF a bit
            diaH -= DIA_STEP
            tPeakMin -= TPEAK_STEP_MIN
            isfMult -= ISF_STEP
        } else {
            // quiet period → decay to baseline/targets
            val k = DECAY_PER_H * (dtMin / 60.0f)
            diaH += (baseDiaH - diaH) * k
            tPeakMin += ((targetPeakMin - tPeakMin).toFloat() * k).toInt()
            isfMult += (targetIsf - isfMult) * k
        }

        // 5) Floors & rails
        val diaCeil = if (SippPrefs.allowDiaAbove9h()) 20.0f else 12.0f
        val floor = when {
            slowSiteActive && falling -> max(DIA_FLOOR_HARD, baseDiaH)
            slowSiteActive            -> max(DIA_FLOOR_SOFT, baseDiaH)
            else                      -> max(DIA_MIN, baseDiaH)
        }

        if (diaH < floor) diaH = (diaH + floor) / 2.0f
        diaH = diaH.coerceIn(DIA_MIN, min(DIA_MAX, diaCeil))
        tPeakMin = tPeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
        isfMult = isfMult.coerceIn(ISF_MIN, ISF_MAX)

        // 6) Persist the live state so updates don't reset behavior
        SippPrefs.saveState(diaH, tPeakMin, isfMult, nowMs)
    }

    private fun decay(dtMin: Float, baseDiaH: Float, basePeakMin: Int) {
        val k = DECAY_PER_H * (dtMin / 60.0f)
        diaH += (baseDiaH - diaH) * k
        tPeakMin += ((basePeakMin - tPeakMin).toFloat() * k).toInt()
        isfMult += (1.0f - isfMult) * k   // target ISF is neutral (×1.00)
    }

    private fun clampAll() {
        val diaCeil = if (SippPrefs.allowDiaAbove9h()) 20.0f else 12.0f
        diaH = diaH.coerceIn(DIA_MIN, min(DIA_MAX, diaCeil))
        tPeakMin = tPeakMin.coerceIn(TPEAK_MIN_MIN, TPEAK_MAX_MIN)
        isfMult = isfMult.coerceIn(ISF_MIN, ISF_MAX)
    }
}

package com.bitbeat.ecg

import kotlin.math.pow

/**
 * Real-time R-peak detector (simplified Pan-Tompkins).
 * Steps: 5-15 Hz band-pass -> derivative -> square -> 150 ms moving average
 * -> adaptive threshold. It works on raw ADC counts, one sample at a time.
 * It was tested with the team's CSV captures (45, 75, 120 BPM and the real ProSim signal).
 */
class RPeakDetector(fs: Double = FS_HZ) {
    private val highPass = Biquad.highPass(fs, 5.0)
    private val lowPass = Biquad.lowPass(fs, 15.0)
    private val windowSize = (0.150 * fs).toInt()            // 150 ms
    private val warmUpSamples = (2.0 * fs).toInt()           // learn the signal level for 2 s
    private val envelopeDecay = 0.5.pow(1.0 / (2.0 * fs))    // the envelope halves in 2 s

    private val history = DoubleArray(5)
    private val window = DoubleArray(windowSize)
    private var windowIndex = 0
    private var windowSum = 0.0
    private var offset = Double.NaN
    private var envelope = 0.0
    private var count = 0

    private var inQrs = false
    private var qrsMax = 0.0
    private var qrsTime = 0L
    private var lastBeatTime = -1L
    private var lastBeatLevel = 0.0

    /** Feeds one sample. Returns the time (t_ms) of a confirmed beat, or null. */
    fun process(adc: Int, tMs: Long): Long? {
        if (offset.isNaN()) offset = adc.toDouble()
        val x = lowPass.process(highPass.process(adc - offset))

        // 5-point derivative
        for (i in 4 downTo 1) history[i] = history[i - 1]
        history[0] = x
        val d = (2 * history[0] + history[1] - history[3] - 2 * history[4]) / 8.0

        // Square + moving average (moving window integration)
        val sq = d * d
        windowSum += sq - window[windowIndex]
        window[windowIndex] = sq
        windowIndex = (windowIndex + 1) % windowSize
        val m = maxOf(windowSum, 0.0) / windowSize

        count++
        envelope = maxOf(m, envelope * envelopeDecay)
        if (count < warmUpSamples) return null

        val threshold = 0.3 * envelope
        if (!inQrs) {
            if (m > threshold) {
                inQrs = true
                qrsMax = m
                qrsTime = tMs
            }
            return null
        }

        if (m > qrsMax) {
            qrsMax = m
            qrsTime = tMs
        }
        if (m >= 0.5 * threshold && tMs - qrsTime <= 300) return null

        // End of the QRS: decide if it is a real beat
        inQrs = false
        if (lastBeatTime >= 0) {
            val dt = qrsTime - lastBeatTime
            if (dt < 200) return null                                   // refractory period
            if (dt < 360 && qrsMax < 0.5 * lastBeatLevel) return null   // probably a T wave
        }
        lastBeatTime = qrsTime
        lastBeatLevel = qrsMax
        return qrsTime
    }

    fun reset() {
        highPass.reset(); lowPass.reset()
        history.fill(0.0); window.fill(0.0)
        windowIndex = 0; windowSum = 0.0
        offset = Double.NaN; envelope = 0.0; count = 0
        inQrs = false; qrsMax = 0.0; qrsTime = 0L
        lastBeatTime = -1L; lastBeatLevel = 0.0
    }
}

/** Adult reference ranges. */
enum class RateCategory(val label: String, val range: String) {
    BRADYCARDIA("Bradycardia", "< 60 bpm"),
    NORMAL("Normal rate", "60–100 bpm"),
    TACHYCARDIA("Tachycardia", "> 100 bpm");

    companion object {
        fun from(bpm: Int): RateCategory = when {
            bpm < 60 -> BRADYCARDIA
            bpm > 100 -> TACHYCARDIA
            else -> NORMAL
        }
    }
}

enum class HeartRateStatus { NO_SIGNAL, DETECTING, VALID, LEAD_OFF }

data class HeartRateInfo(
    val status: HeartRateStatus = HeartRateStatus.NO_SIGNAL,
    val bpm: Int? = null,
    val rrMs: Int? = null,
    val category: RateCategory? = null
)

/**
 * RR intervals and BPM. It uses the ESP32 time (t_ms), never the phone arrival time.
 * BPM = 60000 / median of the last 5 valid RR intervals.
 * It needs at least 3 RR intervals in a row before it shows a value.
 */
class HeartRateTracker {
    private val rr = ArrayDeque<Long>()
    private var lastBeat = -1L

    fun onBeat(tMs: Long) {
        if (lastBeat >= 0) {
            val interval = tMs - lastBeat
            if (interval in 250..2500) {          // 24-240 bpm
                rr.addLast(interval)
                while (rr.size > 8) rr.removeFirst()
            } else {
                rr.clear()
            }
        }
        lastBeat = tMs
    }

    /** Use after a gap or bad contact: never calculate RR across invalid data. */
    fun reset() {
        rr.clear()
        lastBeat = -1L
    }

    fun current(nowMs: Long): HeartRateInfo {
        if (lastBeat < 0 || rr.size < 3) return HeartRateInfo(HeartRateStatus.DETECTING)
        val median = rr.takeLast(5).sorted().let { it[it.size / 2] }
        val timeout = maxOf(3000L, 2 * median)
        if (nowMs - lastBeat > timeout) return HeartRateInfo(HeartRateStatus.DETECTING)
        val bpm = Math.round(60000.0 / median).toInt()
        return HeartRateInfo(HeartRateStatus.VALID, bpm, median.toInt(), RateCategory.from(bpm))
    }
}
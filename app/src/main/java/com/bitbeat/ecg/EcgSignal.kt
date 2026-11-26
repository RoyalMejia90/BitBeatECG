package com.bitbeat.ecg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Biquad filter (RBJ formulas). Processes one sample at a time, in real time. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    companion object {
        private fun make(kind: Char, fs: Double, f0: Double, q: Double): Biquad {
            val w0 = 2 * PI * f0 / fs
            val c = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val (b0, b1, b2) = when (kind) {
                'L' -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)       // low-pass
                'H' -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)    // high-pass
                else -> Triple(1.0, -2 * c, 1.0)                     // notch
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun lowPass(fs: Double, f0: Double, q: Double = 0.7071) = make('L', fs, f0, q)
        fun highPass(fs: Double, f0: Double, q: Double = 0.7071) = make('H', fs, f0, q)
        fun notch(fs: Double, f0: Double, q: Double = 30.0) = make('N', fs, f0, q)
    }
}

/**
 * Filter for the LIVE CHART: 60 Hz notch (power line),
 * 0.5 Hz high-pass (removes baseline drift) and 40 Hz low-pass (muscle noise).
 * The output is still in ADC counts, centred on 0.
 */
class DisplayFilter(fs: Double) {
    private val notch = Biquad.notch(fs, 60.0)
    private val highPass = Biquad.highPass(fs, 0.5)
    private val lowPass = Biquad.lowPass(fs, 40.0)
    private var offset = Double.NaN

    fun process(adc: Double): Double {
        if (offset.isNaN()) offset = adc   // avoids a big jump at the start
        return lowPass.process(highPass.process(notch.process(adc - offset)))
    }

    fun reset() {
        notch.reset(); highPass.reset(); lowPass.reset()
        offset = Double.NaN
    }
}

/**
 * Circular buffer for the monitor sweep (5 s at 250 Hz by default).
 * NaN = gap: nothing is drawn there (samples are never invented).
 * It is written by the WebSocket thread and read by the screen.
 */
class EcgDisplayBuffer(val size: Int = 1250) {
    private val lock = Any()
    private val values = FloatArray(size) { Float.NaN }
    private val leadOff = BooleanArray(size)
    private val beats = BooleanArray(size)
    private var writePos = 0

    fun add(value: Float, isLeadOff: Boolean) = synchronized(lock) {
        values[writePos] = value
        leadOff[writePos] = isLeadOff
        beats[writePos] = false
        writePos = (writePos + 1) % size
    }

    fun addGap(count: Int) = synchronized(lock) {
        repeat(count.coerceIn(0, size)) {
            values[writePos] = Float.NaN
            leadOff[writePos] = false
            beats[writePos] = false
            writePos = (writePos + 1) % size
        }
    }

    /** Marks the highest point in the last [lookBack] samples as an R peak. */
    fun markBeat(lookBack: Int) = synchronized(lock) {
        var best = -1
        var bestValue = -Float.MAX_VALUE
        for (k in 1..lookBack.coerceIn(1, size - 1)) {
            val i = (writePos - k + size) % size
            val v = values[i]
            if (!v.isNaN() && v > bestValue) { bestValue = v; best = i }
        }
        if (best >= 0) beats[best] = true
    }

    fun clear() = synchronized(lock) {
        values.fill(Float.NaN)
        leadOff.fill(false)
        beats.fill(false)
        writePos = 0
    }

    /** Copies the content for drawing. Returns the current sweep position. */
    fun snapshot(outValues: FloatArray, outLeadOff: BooleanArray, outBeats: BooleanArray): Int =
        synchronized(lock) {
            values.copyInto(outValues)
            leadOff.copyInto(outLeadOff)
            beats.copyInto(outBeats)
            writePos
        }
}
package com.bitbeat.ecg

/**
 * One sample of the ESP32 protocol v1: "t_ms,adc,lo_plus,lo_minus".
 * Each WebSocket message contains exactly one sample.
 */
data class EcgSample(
    val tMs: Long,      // ms since the ESP32 started (acquisition time)
    val adc: Int,       // raw ADC counts 0-4095 (NOT mV)
    val loPlus: Int,    // 1 = electrode disconnected
    val loMinus: Int    // 1 = electrode disconnected
) {
    val leadOff: Boolean get() = loPlus == 1 || loMinus == 1
}

object EcgParser {
    /** Returns null if the message has the wrong format or a value is out of range. */
    fun parse(text: String): EcgSample? {
        val f = text.trim().split(',')
        if (f.size != 4) return null
        val t = f[0].toLongOrNull() ?: return null
        val adc = f[1].toIntOrNull() ?: return null
        val lp = f[2].toIntOrNull() ?: return null
        val lm = f[3].toIntOrNull() ?: return null
        if (t !in 0L..4294967295L || adc !in 0..4095 || lp !in 0..1 || lm !in 0..1) return null
        return EcgSample(t, adc, lp, lm)
    }
}
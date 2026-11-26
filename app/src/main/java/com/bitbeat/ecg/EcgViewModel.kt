package com.bitbeat.ecg

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** ESP32 address (protocol v1). */
const val ESP32_URL = "ws://192.168.4.1:81/"

/** Sampling rate of protocol v1. */
const val FS_HZ = 250.0

data class StreamStats(
    val total: Long = 0,        // samples received in this session
    val rateHz: Int = 0,        // samples in the last second (~250)
    val gaps: Long = 0,         // t_ms jumps longer than 8 ms
    val resets: Long = 0,       // t_ms repeated or going back (ESP32 restarted)
    val invalid: Long = 0,      // messages with a wrong format
    val last: EcgSample? = null
)

/** Electrode status from LO+ and LO−. */
data class LeadStatus(val plusOff: Boolean = false, val minusOff: Boolean = false) {
    val anyOff: Boolean get() = plusOff || minusOff
}

class EcgViewModel(app: Application) : AndroidViewModel(app) {

    private val lock = Any()
    private var total = 0L
    private var gaps = 0L
    private var resets = 0L
    private var lastT = -1L
    private var last: EcgSample? = null
    private var countThisSecond = 0
    private var wasLeadOff = false

    /** Filtered signal for the chart (5 s). */
    val display = EcgDisplayBuffer(size = (FS_HZ * 5).toInt())
    private val displayFilter = DisplayFilter(FS_HZ)

    /** Beat detection and heart rate. */
    private val detector = RPeakDetector(FS_HZ)
    private val heart = HeartRateTracker()

    private val client = EcgWebSocketClient(
        context = app,
        url = ESP32_URL,
        onSample = { handleSample(it) },
        onSessionStart = { resetSession() }
    )

    val connection: StateFlow<ConnectionState> = client.state

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats

    private val _lead = MutableStateFlow(LeadStatus())
    val lead: StateFlow<LeadStatus> = _lead

    private val _heartRate = MutableStateFlow(HeartRateInfo())
    val heartRate: StateFlow<HeartRateInfo> = _heartRate

    init {
        client.start()
        // Update the stats and the heart rate once per second (not for every sample)
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                synchronized(lock) {
                    _stats.value = StreamStats(total, countThisSecond, gaps, resets, client.invalidMessages, last)
                    countThisSecond = 0
                    publishHeartRate()
                }
            }
        }
    }

    private fun handleSample(s: EcgSample) {
        synchronized(lock) {
            if (lastT >= 0) {
                val dt = s.tMs - lastT
                when {
                    dt <= 0 -> {                 // the ESP32 restarted: start again
                        resets++
                        restartProcessing()
                    }
                    dt > 2000 -> {               // very long gap: also start again
                        gaps++
                        restartProcessing()
                    }
                    dt > 8 -> {                  // short gap: leave it blank, never invent samples
                        gaps++
                        display.addGap((dt / 4 - 1).toInt())
                        heart.reset()            // never calculate RR across a gap
                    }
                }
            }
            lastT = s.tMs
            last = s
            total++
            countThisSecond++

            val y = displayFilter.process(s.adc.toDouble())
            display.add(y.toFloat(), s.leadOff)

            if (s.leadOff) {
                heart.reset()                    // bad contact: no BPM, no RR
            } else {
                if (wasLeadOff) detector.reset() // contact is back: learn the signal again
                val beatTime = detector.process(s.adc, s.tMs)
                if (beatTime != null) {
                    // Mark the R peak on the chart (it is a few samples behind)
                    display.markBeat(((s.tMs - beatTime) / 4).toInt() + 45)
                    heart.onBeat(beatTime)
                    publishHeartRate()
                }
            }
            if (s.leadOff != wasLeadOff) {
                wasLeadOff = s.leadOff
                publishHeartRate()
            }

            val ls = LeadStatus(s.loPlus == 1, s.loMinus == 1)
            if (ls != _lead.value) _lead.value = ls
        }
    }

    /** Call only inside synchronized(lock). */
    private fun publishHeartRate() {
        _heartRate.value = when {
            lastT < 0 -> HeartRateInfo(HeartRateStatus.NO_SIGNAL)
            wasLeadOff -> HeartRateInfo(HeartRateStatus.LEAD_OFF)
            else -> heart.current(lastT)
        }
    }

    /** Call only inside synchronized(lock). */
    private fun restartProcessing() {
        displayFilter.reset()
        display.clear()
        detector.reset()
        heart.reset()
    }

    private fun resetSession() {
        synchronized(lock) {
            lastT = -1L
            total = 0
            gaps = 0
            resets = 0
            countThisSecond = 0
            last = null
            wasLeadOff = false
            restartProcessing()
            _lead.value = LeadStatus()
            _heartRate.value = HeartRateInfo()
        }
    }

    override fun onCleared() {
        client.stop()
    }
}
package com.bitbeat.ecg

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bitbeat.ecg.ui.theme.BitBeat

/**
 * Live ECG monitor: the trace sweeps from left to right, like a real monitor.
 * - Vertical grid for TIME only (0.2 s and 1 s): time is calibrated.
 * - No amplitude grid in mV: the signal is in uncalibrated ADC counts.
 * - Parts with a disconnected electrode are drawn in the alert colour.
 * - Detected R peaks are marked with a light blue dot.
 */
@Composable
fun EcgChart(buffer: EcgDisplayBuffer, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }   // redraw at the screen refresh rate
    }

    val values = remember { FloatArray(buffer.size) }
    val leadOff = remember { BooleanArray(buffer.size) }
    val beats = remember { BooleanArray(buffer.size) }
    val range = remember { floatArrayOf(-300f, 300f) }   // smoothed vertical scale

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE") val tick = frame
        val pos = buffer.snapshot(values, leadOff, beats)
        val n = buffer.size
        val w = size.width
        val h = size.height
        val dx = w / (n - 1)

        // Time grid: thin line every 0.2 s (50 samples), stronger line every 1 s (250)
        for (i in 0 until n step 50) {
            val major = i % 250 == 0
            drawLine(
                color = if (major) BitBeat.Border else BitBeat.SoftBlue,
                start = Offset(i * dx, 0f), end = Offset(i * dx, h),
                strokeWidth = if (major) 1.5f else 1f
            )
        }

        // Automatic vertical scale (smoothed so it does not "jump")
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in values) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (lo <= hi) {
            val span = maxOf(hi - lo, 150f)
            val mid = (hi + lo) / 2
            val targetLo = mid - span * 0.6f
            val targetHi = mid + span * 0.6f
            range[0] += (targetLo - range[0]) * 0.08f
            range[1] += (targetHi - range[1]) * 0.08f
        }
        val yLo = range[0]
        val ySpan = (range[1] - range[0]).coerceAtLeast(1f)
        fun y(v: Float) = h - (v - yLo) / ySpan * h

        // Trace: it breaks at gaps (NaN) and just ahead of the sweep
        val gapAhead = 20
        val normal = Path()
        val alert = Path()
        var penDown = false
        var lastWasAlert = false
        for (i in 0 until n) {
            val ahead = (i - pos + n) % n
            val v = values[i]
            if (ahead < gapAhead || v.isNaN() || i == 0) {
                penDown = false
                if (v.isNaN() || ahead < gapAhead) continue
            }
            val isAlert = leadOff[i]
            val path = if (isAlert) alert else normal
            val x = i * dx
            if (!penDown || isAlert != lastWasAlert) {
                if (penDown) path.moveTo((i - 1) * dx, y(values[i - 1])) else path.moveTo(x, y(v))
                if (penDown) path.lineTo(x, y(v))
            } else {
                path.lineTo(x, y(v))
            }
            penDown = true
            lastWasAlert = isAlert
        }
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(normal, color = BitBeat.Cobalt, style = stroke)
        drawPath(alert, color = BitBeat.Alert, style = stroke)

        // R-peak markers
        for (i in 0 until n) {
            val ahead = (i - pos + n) % n
            if (beats[i] && ahead >= gapAhead && !values[i].isNaN()) {
                drawCircle(BitBeat.LightBlue, radius = 5.dp.toPx(), center = Offset(i * dx, y(values[i]) - 10.dp.toPx()))
            }
        }

        // Sweep dot
        val last = (pos - 1 + n) % n
        if (!values[last].isNaN()) {
            drawCircle(BitBeat.Cobalt, radius = 4.dp.toPx(), center = Offset(last * dx, y(values[last])))
        }
    }
}
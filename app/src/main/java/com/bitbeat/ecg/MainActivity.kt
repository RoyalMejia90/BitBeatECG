package com.bitbeat.ecg

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitbeat.ecg.ui.theme.BitBeat
import com.bitbeat.ecg.ui.theme.BitBeatECGTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light background: dark icons in the system bars, even if the phone uses dark mode
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            BitBeatECGTheme {
                MonitorScreen()
            }
        }
    }
}

@Composable
fun MonitorScreen(vm: EcgViewModel = viewModel()) {
    val conn by vm.connection.collectAsState()
    val stats by vm.stats.collectAsState()
    val lead by vm.lead.collectAsState()
    val heartRate by vm.heartRate.collectAsState()
    val receiving = conn == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitBeat.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(conn)

        if (receiving && lead.anyOff) LeadOffBanner(lead)

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live ECG", color = BitBeat.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text("ADC counts · 5 s", color = BitBeat.SecondaryText, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            EcgChart(
                buffer = vm.display,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
        }

        HeartRateCard(if (receiving) heartRate else HeartRateInfo(HeartRateStatus.NO_SIGNAL))

        Text(
            "${stats.rateHz} samples/s · gaps ${stats.gaps} · invalid ${stats.invalid}",
            color = BitBeat.SecondaryText,
            fontSize = 12.sp
        )

        Spacer(Modifier.weight(1f))

        Text(
            "Educational result only. This is not a medical diagnosis.",
            color = BitBeat.SecondaryText,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun HeartRateCard(info: HeartRateInfo) {
    Card {
        Text("Heart rate", color = BitBeat.SecondaryText, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                info.bpm?.toString() ?: "—",
                color = BitBeat.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 44.sp
            )
            Spacer(Modifier.width(6.dp))
            Text("bpm", color = BitBeat.SecondaryText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            info.category?.let { CategoryChip(it) }
        }
        val detail = when (info.status) {
            HeartRateStatus.VALID -> "RR ${info.rrMs} ms · median of the last 5 beats"
            HeartRateStatus.DETECTING -> "Detecting heartbeats…"
            HeartRateStatus.LEAD_OFF -> "Paused: an electrode is disconnected"
            HeartRateStatus.NO_SIGNAL -> "No data from the ESP32"
        }
        Text(detail, color = BitBeat.SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Adult reference: < 60 bradycardia · 60–100 normal · > 100 tachycardia",
            color = BitBeat.SecondaryText,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CategoryChip(category: RateCategory) {
    val normal = category == RateCategory.NORMAL
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .background(if (normal) BitBeat.SoftBlue else BitBeat.SoftAlert, RoundedCornerShape(12.dp))
            .border(1.dp, if (normal) BitBeat.Border else BitBeat.Alert, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            category.label,
            color = if (normal) BitBeat.Cobalt else BitBeat.Alert,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Text(category.range, color = BitBeat.SecondaryText, fontSize = 11.sp)
    }
}

@Composable
private fun Header(conn: ConnectionState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("BitBeat", color = BitBeat.Cobalt, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.width(6.dp))
        Text("ECG", color = BitBeat.Cobalt, fontWeight = FontWeight.Normal, fontSize = 24.sp)
        Spacer(Modifier.weight(1f))
        ConnectionChip(conn)
    }
}

@Composable
private fun ConnectionChip(conn: ConnectionState) {
    val (dot, bg, text) = when (conn) {
        ConnectionState.CONNECTED -> Triple(BitBeat.Cobalt, BitBeat.SoftBlue, "Connected · ESP32")
        ConnectionState.NO_DATA -> Triple(BitBeat.Alert, BitBeat.SoftAlert, "No data")
        ConnectionState.RECONNECTING, ConnectionState.CONNECTING ->
            Triple(BitBeat.Alert, BitBeat.SoftAlert, "Connecting…")
        ConnectionState.WAITING_FOR_WIFI -> Triple(BitBeat.SecondaryText, BitBeat.Surface, "No ESP32 Wi-Fi")
        ConnectionState.STOPPED -> Triple(BitBeat.SecondaryText, BitBeat.Surface, "Stopped")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, BitBeat.Border, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = BitBeat.Ink, fontSize = 13.sp)
    }
}

@Composable
private fun LeadOffBanner(lead: LeadStatus) {
    val which = when {
        lead.plusOff && lead.minusOff -> "LO+ and LO−"
        lead.plusOff -> "LO+"
        else -> "LO−"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BitBeat.SoftAlert, RoundedCornerShape(12.dp))
            .border(1.dp, BitBeat.Alert, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("Electrode disconnected ($which)", color = BitBeat.Alert, fontWeight = FontWeight.Bold)
        Text(
            "Check the electrodes. The signal is not valid, so the heart rate is not calculated.",
            color = BitBeat.Ink,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BitBeat.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, BitBeat.Border, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) { content() }
}
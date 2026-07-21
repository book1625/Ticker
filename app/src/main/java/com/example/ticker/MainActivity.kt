package com.example.ticker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var pendingStart: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingStart?.invoke()
        pendingStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MetronomeScreen(
                        onStart = { bpm, durationMillis -> startMetronome(bpm, durationMillis) },
                        onStop = { stopMetronome() }
                    )
                }
            }
        }
    }

    private fun startMetronome(bpm: Int, durationMillis: Long) {
        val launch = {
            val intent = Intent(this, MetronomeService::class.java).apply {
                action = MetronomeService.ACTION_START
                putExtra(MetronomeService.EXTRA_BPM, bpm)
                putExtra(MetronomeService.EXTRA_DURATION_MILLIS, durationMillis)
            }
            ContextCompat.startForegroundService(this, intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = launch
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launch()
        }
    }

    private fun stopMetronome() {
        val intent = Intent(this, MetronomeService::class.java).apply {
            action = MetronomeService.ACTION_STOP
        }
        startService(intent)
    }
}

private val BPM_PRESETS = listOf(160, 180, 200, 210, 220)
private val MINUTE_PRESETS = listOf(15, 20, 25, 30, 35, 40)

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun MetronomeScreen(onStart: (Int, Long) -> Unit, onStop: () -> Unit) {
    var bpmText by rememberSaveable { mutableStateOf("120") }
    var minutesText by rememberSaveable { mutableStateOf("15") }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var isPaused by rememberSaveable { mutableStateOf(false) }
    var startElapsedRealtime by rememberSaveable { mutableStateOf(0L) }
    var totalDurationMillis by rememberSaveable { mutableStateOf(0L) }
    var remainingMillis by rememberSaveable { mutableStateOf(0L) }

    LaunchedEffect(isRunning, startElapsedRealtime) {
        while (isRunning) {
            val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtime
            val remaining = totalDurationMillis - elapsed
            if (remaining <= 0) {
                remainingMillis = 0
                isRunning = false
                isPaused = false
            } else {
                remainingMillis = remaining
                delay(200)
            }
        }
    }

    val fieldsEnabled = !isRunning && !isPaused
    val displayedMillis = when {
        isRunning -> remainingMillis
        isPaused -> remainingMillis
        else -> (minutesText.toIntOrNull() ?: 15) * 60_000L
    }

    val onReset: () -> Unit = {
        if (isRunning) onStop()
        isRunning = false
        isPaused = false
        remainingMillis = (minutesText.toIntOrNull() ?: 15) * 60_000L
    }

    val onToggle: () -> Unit = {
        if (isRunning) {
            onStop()
            isRunning = false
            isPaused = true
        } else {
            val bpm = bpmText.toIntOrNull()?.coerceIn(30, 240) ?: 120
            val durationMillis = if (isPaused) {
                remainingMillis
            } else {
                (minutesText.toIntOrNull()?.coerceIn(1, 180) ?: 15) * 60_000L
            }
            totalDurationMillis = durationMillis
            startElapsedRealtime = SystemClock.elapsedRealtime()
            onStart(bpm, durationMillis)
            isRunning = true
            isPaused = false
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                DisplaySection(bpmText = bpmText, displayedMillis = displayedMillis)
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                SelectionSection(
                    bpmText = bpmText,
                    onBpmTextChange = { bpmText = it },
                    minutesText = minutesText,
                    onMinutesTextChange = { minutesText = it },
                    fieldsEnabled = fieldsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                ButtonSection(
                    isRunning = isRunning,
                    onReset = onReset,
                    onToggle = onToggle,
                    stacked = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            DisplaySection(bpmText = bpmText, displayedMillis = displayedMillis)
            Spacer(modifier = Modifier.height(32.dp))
            SelectionSection(
                bpmText = bpmText,
                onBpmTextChange = { bpmText = it },
                minutesText = minutesText,
                onMinutesTextChange = { minutesText = it },
                fieldsEnabled = fieldsEnabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))
            ButtonSection(
                isRunning = isRunning,
                onReset = onReset,
                onToggle = onToggle,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 提示區：以大字體顯示目前 BPM 與倒計時剩餘時間。 */
@Composable
private fun DisplaySection(bpmText: String, displayedMillis: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = bpmText.ifEmpty { "0" },
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, fontWeight = FontWeight.Bold)
        )
        Text(text = "BPM", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = formatRemaining(displayedMillis),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp, fontWeight = FontWeight.Bold)
        )
    }
}

/** 選擇區：BPM 與倒數分鐘數的輸入欄位及快選項。 */
@Composable
private fun SelectionSection(
    bpmText: String,
    onBpmTextChange: (String) -> Unit,
    minutesText: String,
    onMinutesTextChange: (String) -> Unit,
    fieldsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = bpmText,
            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) onBpmTextChange(it) },
            label = { Text("節拍 (BPM)") },
            enabled = fieldsEnabled,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BPM_PRESETS.forEach { preset ->
                FilterChip(
                    selected = bpmText == preset.toString(),
                    onClick = { onBpmTextChange(preset.toString()) },
                    enabled = fieldsEnabled,
                    label = { Text(preset.toString()) }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = minutesText,
            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) onMinutesTextChange(it) },
            label = { Text("倒數分鐘數") },
            enabled = fieldsEnabled,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MINUTE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = minutesText == preset.toString(),
                    onClick = { onMinutesTextChange(preset.toString()) },
                    enabled = fieldsEnabled,
                    label = { Text(preset.toString()) }
                )
            }
        }
    }
}

/** 按鍵區：重置與開始/暫停，等寬圓形按鈕。直向時左右並排，橫向時改為上下排列。 */
@Composable
private fun ButtonSection(
    isRunning: Boolean,
    onReset: () -> Unit,
    onToggle: () -> Unit,
    stacked: Boolean = false,
    modifier: Modifier = Modifier
) {
    val resetButton = @Composable {
        Button(
            onClick = onReset,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.size(96.dp)
        ) {
            Text("重置")
        }
    }
    val toggleButton = @Composable {
        Button(
            onClick = onToggle,
            shape = CircleShape,
            modifier = Modifier.size(96.dp)
        ) {
            Text(if (isRunning) "暫停" else "開始")
        }
    }

    if (stacked) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { resetButton() }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { toggleButton() }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { resetButton() }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { toggleButton() }
        }
    }
}

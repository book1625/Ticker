package com.example.ticker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class MetronomeService : Service() {

    companion object {
        const val ACTION_START = "com.example.ticker.action.START"
        const val ACTION_STOP = "com.example.ticker.action.STOP"
        const val ACTION_SET_VOLUME = "com.example.ticker.action.SET_VOLUME"
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_DURATION_MILLIS = "extra_duration_millis"
        const val EXTRA_VOLUME = "extra_volume"
        private const val NOTIFICATION_CHANNEL_ID = "metronome_channel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44100
        private const val CHIME_INTERVAL_NANOS = 5L * 60_000_000_000L
    }

    @Volatile
    private var isRunning = false
    private var beatThread: Thread? = null
    private var tiTrack: AudioTrack? = null
    private var taTrack: AudioTrack? = null
    private var chimeTrack: AudioTrack? = null
    private var volume = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tiTrack = createClickTrack(frequencyHz = 1080.0, durationMs = 50)
        taTrack = createClickTrack(frequencyHz = 900.0, durationMs = 50)
        chimeTrack = createChimeTrack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMetronome()
            ACTION_SET_VOLUME -> {
                volume = intent.getFloatExtra(EXTRA_VOLUME, 1f)
                applyVolume()
                if (!isRunning) stopSelf()
            }
            ACTION_START -> {
                val bpm = intent.getIntExtra(EXTRA_BPM, 120)
                val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 15 * 60_000L)
                volume = intent.getFloatExtra(EXTRA_VOLUME, 1f)
                applyVolume()
                startMetronome(bpm, durationMillis)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        beatThread?.interrupt()
        tiTrack?.release()
        taTrack?.release()
        chimeTrack?.release()
        super.onDestroy()
    }

    private fun startMetronome(bpm: Int, durationMillis: Long) {
        if (isRunning) return
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification(bpm, durationMillis))
        beatThread = Thread { runBeatLoop(bpm, durationMillis) }.apply { start() }
    }

    private fun stopMetronome() {
        isRunning = false
        beatThread?.interrupt()
        beatThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 依 BPM 換算成每拍間隔（奈秒），以絕對時間對齊目標拍點，避免長時間播放後產生時間漂移。 */
    private fun runBeatLoop(bpm: Int, durationMillis: Long) {
        val intervalNanos = 60_000_000_000L / bpm
        val durationNanos = durationMillis * 1_000_000L
        val startNanos = System.nanoTime()
        var beatIndex = 0L
        var nextChimeNanos = CHIME_INTERVAL_NANOS

        try {
            while (isRunning && (System.nanoTime() - startNanos) < durationNanos) {
                val targetNanos = startNanos + beatIndex * intervalNanos
                val waitNanos = targetNanos - System.nanoTime()
                if (waitNanos > 0) {
                    Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
                }
                if (!isRunning) break
                // 到達間隔時間的那一拍，改以長音叮聲取代 ti-ta 拍點聲
                if (System.nanoTime() - startNanos >= nextChimeNanos) {
                    playClick(chimeTrack)
                    nextChimeNanos += CHIME_INTERVAL_NANOS
                } else {
                    playClick(if (beatIndex % 2 == 0L) tiTrack else taTrack)
                }
                beatIndex++
            }
            if (isRunning) playEndChimes()
        } catch (_: InterruptedException) {
            // 由 stopMetronome() 中斷，正常結束
        } finally {
            stopSelf()
        }
    }

    /** 倒計時自然結束時連播三次叮聲作為結束提示，間隔略長於叮聲長度，讓每聲鈴音尾韻收完再播下一聲。 */
    private fun playEndChimes() {
        repeat(3) {
            playClick(chimeTrack)
            Thread.sleep(600)
        }
    }

    private fun playClick(track: AudioTrack?) {
        track ?: return
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    /** 將目前音量套用到所有音軌，疊加在系統媒體音量之上。 */
    private fun applyVolume() {
        tiTrack?.setVolume(volume)
        taTrack?.setVolume(volume)
        chimeTrack?.setVolume(volume)
    }

    /**
     * 合成木魚風格的點擊聲，音色與包絡對齊 web 版。
     * 主體為三角波，起音時音高為 1.6 倍基頻並在 10ms 內指數滑回基頻，搭配極快衰減形成中空的「喀」；
     * 疊加 2.6 倍不諧和高泛音增添木魚音色，並以高通後的極短噪音作為起音觸點。整體為極短的打擊式包絡。
     */
    private fun createClickTrack(frequencyHz: Double, durationMs: Int): AudioTrack {
        val numSamples = SAMPLE_RATE * durationMs / 1000
        val buffer = ShortArray(numSamples)
        val random = java.util.Random()

        // 起音音高由 1.6 倍基頻在 10ms 內指數滑回基頻（對齊 web 的 exponentialRamp）
        val startFreq = frequencyHz * 1.6
        val glideTime = 0.01
        val glideRatio = frequencyHz / startFreq
        var phase = 0.0

        // 觸點噪音的一階高通狀態（截止約 2200Hz，只留敲擊亮點）
        val hpCoeff = 0.76
        var hpPrevIn = 0.0
        var hpPrevOut = 0.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE

            val f = if (t < glideTime) startFreq * glideRatio.pow(t / glideTime) else frequencyHz
            phase += 2.0 * PI * f / SAMPLE_RATE

            // 主體：三角波，快速衰減（與 web 相同的音色與包絡，約 5.5ms 時間常數）
            val tri = (2.0 / PI) * asin(sin(phase))
            val body = tri * 0.9 * (0.0001 / 0.9).pow(t / 0.05)

            // 不諧和高泛音：正弦，更快衰減
            val overtone = sin(2.0 * PI * frequencyHz * 2.6 * t) * 0.14 * (0.0001 / 0.14).pow(t / 0.025)

            // 起音觸點：高通後的極短噪音
            var tick = 0.0
            if (t < 0.006) {
                val white = random.nextDouble() * 2.0 - 1.0
                val hp = hpCoeff * (hpPrevOut + white - hpPrevIn)
                hpPrevIn = white
                hpPrevOut = hp
                tick = hp * 0.15 * (0.0001 / 0.15).pow(t / 0.006)
            }

            val sample = (body + overtone + tick).coerceIn(-1.0, 1.0)
            buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }

        return buildStaticTrack(buffer)
    }

    /**
     * 合成每五分鐘倒數提醒用的高亮叮聲，音色與節拍點擊聲明顯區隔。
     * 疊加基頻與泛音（3 倍頻）模擬鐘鈴的明亮音色，並以指數衰減取代點擊聲的短促收尾，
     * 讓提醒聲帶有較長的鈴音尾韻，在節拍聲中更容易被辨識出來。
     */
    private fun createChimeTrack(): AudioTrack {
        val durationMs = 1500
        val frequencyHz = 2000.0
        val numSamples = SAMPLE_RATE * durationMs / 1000
        val attackSamples = (SAMPLE_RATE * 0.005).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val decayT = (i - attackSamples).coerceAtLeast(0).toDouble() / SAMPLE_RATE
            val envelope = if (i < attackSamples) {
                i.toDouble() / attackSamples
            } else {
                exp(-6.0 * decayT)
            }
            val tone = sin(2.0 * PI * frequencyHz * t) * 0.7 + sin(2.0 * PI * frequencyHz * 3 * t) * 0.3
            buffer[i] = (tone * envelope * Short.MAX_VALUE).toInt().toShort()
        }
        return buildStaticTrack(buffer)
    }

    private fun buildStaticTrack(buffer: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buffer, 0, buffer.size)
        track.setVolume(1.0f)
        return track
    }

    private fun buildNotification(bpm: Int, durationMillis: Long): Notification {
        val durationMinutes = ((durationMillis + 59_999L) / 60_000L).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, MetronomeService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, bpm, durationMinutes))
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }
}

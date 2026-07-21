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
import kotlin.math.exp
import kotlin.math.sin

class MetronomeService : Service() {

    companion object {
        const val ACTION_START = "com.example.ticker.action.START"
        const val ACTION_STOP = "com.example.ticker.action.STOP"
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_DURATION_MILLIS = "extra_duration_millis"
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tiTrack = createClickTrack(frequencyHz = 1500.0, durationMs = 120)
        taTrack = createClickTrack(frequencyHz = 900.0, durationMs = 120)
        chimeTrack = createChimeTrack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMetronome()
            ACTION_START -> {
                val bpm = intent.getIntExtra(EXTRA_BPM, 120)
                val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 15 * 60_000L)
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
                playClick(if (beatIndex % 2 == 0L) tiTrack else taTrack)
                if (System.nanoTime() - startNanos >= nextChimeNanos) {
                    playClick(chimeTrack)
                    nextChimeNanos += CHIME_INTERVAL_NANOS
                }
                beatIndex++
            }
        } catch (_: InterruptedException) {
            // 由 stopMetronome() 中斷，正常結束
        } finally {
            stopSelf()
        }
    }

    private fun playClick(track: AudioTrack?) {
        track ?: return
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    /**
     * 以指定頻率合成點擊聲，避免額外附帶音檔素材。
     * 極短的指數衰減聲音在聽感上天生偏小聲（人耳對短於約 200ms 的音需要更長時間才能感知到完整響度），
     * 因此改用「快速起音、滿振幅維持、短促收尾」的包絡，讓聲音大部分時間維持在最大振幅，明顯提升音量觀感。
     */
    private fun createClickTrack(frequencyHz: Double, durationMs: Int): AudioTrack {
        val numSamples = SAMPLE_RATE * durationMs / 1000
        val attackSamples = (SAMPLE_RATE * 0.005).toInt()
        val releaseSamples = (SAMPLE_RATE * 0.02).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = when {
                i < attackSamples -> i.toDouble() / attackSamples
                i >= numSamples - releaseSamples -> (numSamples - i).toDouble() / releaseSamples
                else -> 1.0
            }
            buffer[i] = (sin(2.0 * PI * frequencyHz * t) * envelope * Short.MAX_VALUE).toInt().toShort()
        }
        return buildStaticTrack(buffer)
    }

    /**
     * 合成每五分鐘倒數提醒用的高亮叮聲，音色與節拍點擊聲明顯區隔。
     * 疊加基頻與泛音（3 倍頻）模擬鐘鈴的明亮音色，並以指數衰減取代點擊聲的短促收尾，
     * 讓提醒聲帶有較長的鈴音尾韻，在節拍聲中更容易被辨識出來。
     */
    private fun createChimeTrack(): AudioTrack {
        val durationMs = 500
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

package de.thb.sonaro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.voicute.wakeword.AudioCapture
import com.voicute.wakeword.DetectionLogic
import com.voicute.wakeword.WakeWordEngine
import kotlin.math.sqrt

class SonaroWakeWordService : Service(), AudioCapture.Listener {
    inner class LocalBinder : Binder() {
        fun getService(): SonaroWakeWordService = this@SonaroWakeWordService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    private companion object {
        const val TAG = "Sonaro"
        const val CHANNEL_ID = "sonaro_wake_word_channel"
        const val INFERENCE_INTERVAL_MS = 30L            // ~33 fps inference
        const val CAPTURE_RESTART_IDLE_MS = 1_800_000L   // restart capture after 30 min without audio activity
        const val MIN_RESTART_GAP_MS = 600_000L          // never restart capture more often than every 10 min
        const val RMS_GATE = 30f                         // skip inference when near-silent (saves CPU/battery)
        const val THRESHOLD = 0.50f                      // detection probability threshold
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // Owned and cleaned up by the inference thread
    private var inferThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sonaro")
            .setContentText("Android requires apps to show a notification for foreground services. You can disable notifications from Sonaro, functionality will not be affected.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        startWakeWordDetection()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sonaro wake word detection",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onMicError(msg: String?) {
        Log.e(TAG, "Microphone error: $msg")
    }

    private fun startWakeWordDetection() {
        println("startWakeWordDetection")

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission is missing")
            return
        }

        // onStartCommand can run multiple times (START_STICKY, repeated startService calls)
        if (inferThread?.isAlive == true) {
            Log.w(TAG, "Wake word detection already running, ignoring duplicate start")
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (wakeLock == null) {
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonaro:WakeWord")
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()

        running = true
        inferThread = Thread(::inferenceLoop, "SonaroInference").also { it.start() }
    }

    /**
     * Runs on a dedicated thread. Loads the model (slow, so not on the main thread),
     * then reads audio from the capture ring buffer, runs the engine and feeds the
     * result through DetectionLogic. Releases everything it created when it exits.
     */
    private fun inferenceLoop() {
        val engine = WakeWordEngine(applicationContext)
        if (!engine.isLoaded) {
            Log.e(TAG, "Failed to load wake word model: ${engine.errorMessage}")
            running = false
            engine.close()
            return
        }
        Log.i(TAG, "Loaded ${engine.modelCount} model(s), wake word: ${engine.wakeWordDisplay}")

        val audioCapture = AudioCapture()
        audioCapture.setListener(this)
        val detection = DetectionLogic()
        detection.reset()

        try {
            audioCapture.start()

            // Warm-up: let the ring buffer fill with audio
            Thread.sleep(500)

            var lastCaptureRestartMs = 0L
            var lastHeartbeat = 0L

            while (running && !Thread.currentThread().isInterrupted) {
                val t0 = System.currentTimeMillis()

                // Watchdog: restart capture if its thread died (with a gap to avoid restart storms)
                if (!audioCapture.isAlive) {
                    Log.w(TAG, "Capture dead, restarting")
                    if (t0 - lastCaptureRestartMs >= MIN_RESTART_GAP_MS) {
                        lastCaptureRestartMs = t0
                        audioCapture.restart()
                        Thread.sleep(800)
                        if (!audioCapture.isAlive) {
                            Log.e(TAG, "Microphone could not be restarted, stopping detection")
                            break
                        }
                    }
                }

                // Idle restart to prevent the audio hardware from going to sleep
                val lastActivityMs = audioCapture.lastAudioActivityMs
                if (audioCapture.isAlive && lastActivityMs > 0
                    && (t0 - lastActivityMs) > CAPTURE_RESTART_IDLE_MS
                    && (t0 - lastCaptureRestartMs) >= MIN_RESTART_GAP_MS
                ) {
                    Log.w(TAG, "Audio idle for 30 min, restarting capture")
                    lastCaptureRestartMs = t0
                    audioCapture.restart()
                }

                // Latest audio chunk from the lock-free ring buffer
                val pos = audioCapture.ringPos
                val chunk = audioCapture.readChunk(pos, engine.audioSamplesNeeded)

                // RMS gate: skip inference on near-silence
                var sumSq = 0f
                for (s in chunk) sumSq += s.toFloat() * s.toFloat()
                val rms = sqrt(sumSq / chunk.size)

                var word = ""
                var prob = 0f
                var modelConsFrames = 5
                if (rms >= RMS_GATE) {
                    val result = engine.process(chunk)
                    if (result != null) {
                        word = result.wakeWord ?: ""
                        prob = result.probability
                        modelConsFrames = result.recommendedConsFrames
                    }
                }

                val now = System.currentTimeMillis()
                detection.record(prob, word, rms, now)
                val triggered: String? = detection.evaluate(
                    word, prob, rms, THRESHOLD, modelConsFrames, 0, now
                )

                if (triggered != null) {
                    val triggerProb = detection.lastTrigProb
                    Log.i(TAG, "Detected '$triggered' (${"%.0f".format(triggerProb * 100)}%), rms=${rms.toInt()}")
                    mainHandler.post { handleWakeWordDetected(triggered, triggerProb) }
                }

                if (now - lastHeartbeat > 30_000) {
                    lastHeartbeat = now
                    Log.i(TAG, "Inference alive | captureAlive=${audioCapture.isAlive}")
                }

                val sleepMs = INFERENCE_INTERVAL_MS - (System.currentTimeMillis() - t0)
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
        } catch (_: InterruptedException) {
            // service is stopping
        } catch (e: Exception) {
            Log.e(TAG, "Wake word detection crashed", e)
        } finally {
            audioCapture.stop()
            engine.close()
        }
    }

    private fun handleWakeWordDetected(word: String, probability: Float) {
        // TODO Trigger actions such as flashing flashlight and playing music
        println("Sonaro wake word detected!")
    }

    private fun stopWakeWordDetection() {
        running = false
        inferThread?.interrupt()
        inferThread = null // the thread stops the capture and closes the engine itself

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        println("stopWakeWordDetection")
        stopWakeWordDetection()
        super.onDestroy()
    }
}
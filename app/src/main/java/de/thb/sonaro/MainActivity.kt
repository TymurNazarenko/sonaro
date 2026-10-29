package de.thb.sonaro

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest.permission.WAKE_LOCK;
import android.Manifest.permission.RECORD_AUDIO;
import android.Manifest.permission.POST_NOTIFICATIONS;
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : ComponentActivity() {

    private var service: SonaroWakeWordService? = null
    private var isBound = false
    private var isActivityStarted = false
    private lateinit var button: Button

    // Tabs
    private lateinit var mainTab: View
    private lateinit var settingsTab: View
    private lateinit var tabMain: TextView
    private lateinit var tabSettings: TextView

    // Settings controls
    private lateinit var flashCheckBox: CheckBox
    private lateinit var unlockCheckBox: CheckBox
    private lateinit var soundCheckBox: CheckBox
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var volumeValue: TextView
    private lateinit var durationSeekBar: SeekBar
    private lateinit var durationValue: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as SonaroWakeWordService.LocalBinder).getService()
            service = s
            s.stateListener = { enabled -> updateButton(enabled) }
            updateButton(s.isDetectionEnabled)
            loadSettings(s)
            Log.d("MainActivity", "Connected to SonaroWakeWordService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            updateButton(false)
            setSettingsEnabled(false)
            Log.d("MainActivity", "Disconnected from SonaroWakeWordService")
        }
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startWakeWordService()
        } else {
            Log.e("MainActivity", "Microphone permission denied")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startWakeWordService()
        } else {
            Log.e("MainActivity", "Notification permission denied")
        }
    }

    private val wakeLockPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startWakeWordService()
        } else {
            Log.e("MainActivity", "Wake lock permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button = findViewById(R.id.centerButton)
        updateButton(false) // red until we know the service state

        setupTabs()
        setupSettings()

        button.setOnClickListener {
            val s = service
            if (s == null) {
                Toast.makeText(this, "Service not connected yet", Toast.LENGTH_SHORT).show()
            } else {
                s.setDetectionEnabled(!s.isDetectionEnabled) // listener updates the button
            }
        }
    }

    // ---- Tabs ----

    private fun setupTabs() {
        mainTab = findViewById(R.id.mainTab)
        settingsTab = findViewById(R.id.settingsTab)
        tabMain = findViewById(R.id.tabMain)
        tabSettings = findViewById(R.id.tabSettings)

        tabMain.setOnClickListener { showTab(settings = false) }
        tabSettings.setOnClickListener { showTab(settings = true) }
        showTab(settings = false)
    }

    private fun showTab(settings: Boolean) {
        mainTab.visibility = if (settings) View.GONE else View.VISIBLE
        settingsTab.visibility = if (settings) View.VISIBLE else View.GONE

        val active = Color.parseColor("#B39DFF")
        val inactive = Color.parseColor("#80FFFFFF")
        tabMain.setTextColor(if (settings) inactive else active)
        tabSettings.setTextColor(if (settings) active else inactive)
        tabMain.setTypeface(null, if (settings) Typeface.NORMAL else Typeface.BOLD)
        tabSettings.setTypeface(null, if (settings) Typeface.BOLD else Typeface.NORMAL)
    }

    // ---- Settings ----

    private fun setupSettings() {
        flashCheckBox = findViewById(R.id.flashCheckBox)
        unlockCheckBox = findViewById(R.id.unlockCheckBox)
        soundCheckBox = findViewById(R.id.soundCheckBox)
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        brightnessValue = findViewById(R.id.brightnessValue)
        volumeValue = findViewById(R.id.volumeValue)
        durationSeekBar = findViewById(R.id.durationSeekBar)
        durationValue = findViewById(R.id.durationValue)

        // Controls stay disabled until the service is connected
        setSettingsEnabled(false)
        setupDurationListener()

        flashCheckBox.setOnCheckedChangeListener { _, checked -> service?.flashFlashlight = checked }
        unlockCheckBox.setOnCheckedChangeListener { _, checked -> service?.unlockScreen = checked }
        soundCheckBox.setOnCheckedChangeListener { _, checked -> service?.playSound = checked }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                brightnessValue.text = "$progress%"
                if (fromUser) service?.brightnessPercent = progress
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                volumeValue.text = "$progress%"
                if (fromUser) service?.volumePercent = progress
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    /** Seekbar progress 0..29 maps to 1..30 seconds. */
    private fun setupDurationListener() {
        durationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = progress + 1
                durationValue.text = seconds.toString()
                if (fromUser) service?.durationSeconds = seconds
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    /** Shows the service's stored settings in the UI (any write-back just stores the same value again). */
    private fun loadSettings(s: SonaroWakeWordService) {
        flashCheckBox.isChecked = s.flashFlashlight
        unlockCheckBox.isChecked = s.unlockScreen
        soundCheckBox.isChecked = s.playSound
        brightnessSeekBar.progress = s.brightnessPercent
        volumeSeekBar.progress = s.volumePercent
        durationSeekBar.progress = s.durationSeconds - 1
        setSettingsEnabled(true)
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        flashCheckBox.isEnabled = enabled
        unlockCheckBox.isEnabled = enabled
        soundCheckBox.isEnabled = enabled
        brightnessSeekBar.isEnabled = enabled
        volumeSeekBar.isEnabled = enabled
        durationSeekBar.isEnabled = enabled
    }

    private fun updateButton(enabled: Boolean) {
        val color = if (enabled) Color.rgb(46, 160, 67) else Color.rgb(200, 50, 50)
        button.backgroundTintList = ColorStateList.valueOf(color)
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true

        if (hasAllPermissions()) {
            startWakeWordService()
        } else {
            if (!hasPermission(RECORD_AUDIO)) {
                microphonePermissionLauncher.launch(RECORD_AUDIO)
            }

            if (!hasPermission(POST_NOTIFICATIONS)) {
                notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
            }

            if (!hasPermission(WAKE_LOCK)) {
                wakeLockPermissionLauncher.launch(WAKE_LOCK)
            }

            if (!Settings.System.canWrite(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName".toUri()))
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllPermissions(): Boolean {
        return hasPermission(RECORD_AUDIO) && hasPermission(POST_NOTIFICATIONS) && hasPermission(POST_NOTIFICATIONS) && Settings.System.canWrite(this)
    }

    private fun startWakeWordService() {
        if (!isActivityStarted || isBound) return
        if (!hasAllPermissions()) return

        val intent = Intent(this, SonaroWakeWordService::class.java)

        try {
            ContextCompat.startForegroundService(this, intent)

            isBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)

            if (!isBound) {
                Log.e("MainActivity", "Failed to bind to SonaroWakeWordService")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start or bind service", e)
        }
    }

    override fun onStop() {
        isActivityStarted = false

        if (isBound) {
            service?.stateListener = null
            setSettingsEnabled(false)
            unbindService(connection)
            isBound = false
            service = null
            Log.d("MainActivity", "Unbound from SonaroWakeWordService")
        }

        super.onStop()
    }
}
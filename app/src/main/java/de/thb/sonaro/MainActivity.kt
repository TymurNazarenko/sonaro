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

class MainActivity : ComponentActivity() {

    private var service: SonaroWakeWordService? = null
    private var isBound = false
    private var isActivityStarted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as SonaroWakeWordService.LocalBinder).getService()
            Log.d("MainActivity", "Connected to SonaroWakeWordService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
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

        val button = findViewById<Button>(R.id.centerButton)
        // TODO: Make the button reflect whether or not the service is running

        button.setOnClickListener {
            // TODO: Toggle service activity
            Toast.makeText(this, "Clicked", Toast.LENGTH_SHORT).show()
        }
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
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllPermissions(): Boolean {
        return hasPermission(RECORD_AUDIO) && hasPermission(POST_NOTIFICATIONS) && hasPermission(POST_NOTIFICATIONS)
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
            unbindService(connection)
            isBound = false
            service = null
            Log.d("MainActivity", "Unbound from SonaroWakeWordService")
        }

        super.onStop()
    }
}
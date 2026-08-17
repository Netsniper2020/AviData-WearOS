package com.avidata.wear.data

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GpsService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "avidata_gps"
        const val NOTIFICATION_ID = 1

        private val _location = MutableStateFlow<Location?>(null)
        val location: StateFlow<Location?> = _location

        private val _hasGps = MutableStateFlow(false)
        val hasGps: StateFlow<Boolean> = _hasGps

        private val _gpsStale = MutableStateFlow(true)
        val gpsStale: StateFlow<Boolean> = _gpsStale

        private val _vSpeedFpm = MutableStateFlow<Float?>(null)
        val vSpeedFpm: StateFlow<Float?> = _vSpeedFpm

        private val _pressureHpa = MutableStateFlow<Float?>(null)
        val pressureHpa: StateFlow<Float?> = _pressureHpa

        private val _temperatureC = MutableStateFlow<Float?>(null)
        val temperatureC: StateFlow<Float?> = _temperatureC

        private var lastGpsTimeMs = 0L
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorMgr: SensorManager
    private var prevAltM: Double? = null
    private var prevAltTime: Long = 0L
    private var smoothedVSpeed = 0f
    private val handler = Handler(Looper.getMainLooper())

    // Staleness checker every 500ms
    private val stalenessCheck = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - lastGpsTimeMs
            _gpsStale.value = (lastGpsTimeMs == 0L || elapsed > 2000)
            handler.postDelayed(this, 500)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _location.value = loc
            _hasGps.value = true
            lastGpsTimeMs = SystemClock.elapsedRealtime()
            _gpsStale.value = false

            // Vario computation
            val now = SystemClock.elapsedRealtime()
            val currentAlt = loc.altitude
            val prev = prevAltM
            if (prev != null && prevAltTime > 0) {
                val dtMs = now - prevAltTime
                if (dtMs in 1..5000) {
                    val dtSec = dtMs / 1000f
                    val dAltFt = ((currentAlt - prev) * 3.28084).toFloat()
                    val rawFpm = (dAltFt / dtSec) * 60f
                    smoothedVSpeed = smoothedVSpeed * 0.7f + rawFpm * 0.3f
                    _vSpeedFpm.value = smoothedVSpeed
                }
            }
            prevAltM = currentAlt
            prevAltTime = now
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorMgr = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Barometer
        sensorMgr.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Ambient temperature
        sensorMgr.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        handler.post(stalenessCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AviData")
            .setContentText("GPS actif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> _pressureHpa.value = event.values[0]
            Sensor.TYPE_AMBIENT_TEMPERATURE -> _temperatureC.value = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        sensorMgr.unregisterListener(this)
        handler.removeCallbacks(stalenessCheck)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "GPS AviData",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "GPS tracking pour AviData" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}

package com.avidata.wear.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

        private val _vSpeedBaroFpm = MutableStateFlow<Float?>(null)
        val vSpeedBaroFpm: StateFlow<Float?> = _vSpeedBaroFpm

        private val _pressureHpa = MutableStateFlow<Float?>(null)
        val pressureHpa: StateFlow<Float?> = _pressureHpa

        private val _temperatureC = MutableStateFlow<Float?>(null)
        val temperatureC: StateFlow<Float?> = _temperatureC

        private var lastGpsTimeMs = 0L
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorMgr: SensorManager

    // GPS vario state
    private var prevAltM: Double? = null
    private var prevAltTime = 0L
    private var smoothedVSpeed = 0f

    // Baro vario state
    private var prevPressureAltFt: Float? = null
    private var prevPressureTime = 0L
    private var smoothedBaroVSpeed = 0f

    private val handler = android.os.Handler(Looper.getMainLooper())

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

            // GPS vario — only with valid altitude
            val now = SystemClock.elapsedRealtime()
            if (loc.hasAltitude() && !(loc.altitude == 0.0 && (!loc.hasAccuracy() || loc.accuracy > 20f))) {
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorMgr = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val pressureSensor: Sensor? = sensorMgr.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor != null) {
            sensorMgr.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        val tempSensor: Sensor? = sensorMgr.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        if (tempSensor != null) {
            sensorMgr.registerListener(this, tempSensor, SensorManager.SENSOR_DELAY_NORMAL)
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
            Sensor.TYPE_PRESSURE -> {
                val p = event.values[0]
                _pressureHpa.value = p

                // Baro vario from pressure altitude change
                val paFt = (145366.45 * (1.0 - Math.pow((p / 1013.25).toDouble(), 0.190284))).toFloat()
                val now = SystemClock.elapsedRealtime()
                val prevPA = prevPressureAltFt
                if (prevPA != null && prevPressureTime > 0) {
                    val dtMs = now - prevPressureTime
                    if (dtMs in 500..5000) {
                        val dtSec = dtMs / 1000f
                        val rawFpm = ((paFt - prevPA) / dtSec) * 60f
                        smoothedBaroVSpeed = smoothedBaroVSpeed * 0.92f + rawFpm * 0.08f
                        _vSpeedBaroFpm.value = smoothedBaroVSpeed
                    }
                }
                prevPressureAltFt = paFt
                prevPressureTime = now
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                _temperatureC.value = event.values[0]
            }
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

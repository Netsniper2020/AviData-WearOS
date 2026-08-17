package com.avidata.wear.data

import android.location.Location
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.pow

enum class DataFieldType {
    NONE, GPS_ALT, QNH_ALT, GPS_QNH_ALT, GROUND_SPEED, GPS_TRACK,
    V_SPEED, LAT, LON, UTC_TIME, LOCAL_TIME, BATTERY, PRESSURE,
    GPS_ACCURACY, OAT, DENSITY_ALT
}

data class FieldData(
    val label: String,
    val value: String,
    val unit: String = "",
    // For dual display (GPS+QNH)
    val label2: String? = null,
    val value2: String? = null,
    val isDual: Boolean = false
)

object AviDataProvider {

    fun getFieldData(
        type: DataFieldType,
        location: Location?,
        hasGps: Boolean,
        gpsStale: Boolean,
        qnhHpa: Int,
        geoidM: Int,
        vSpeedFpm: Float?,
        pressureHpa: Float?,
        temperatureC: Float?,
        batteryPct: Int
    ): FieldData = when (type) {
        DataFieldType.GPS_ALT -> gpsAlt(location, hasGps, gpsStale, geoidM)
        DataFieldType.QNH_ALT -> qnhAlt(location, hasGps, gpsStale, qnhHpa, geoidM, pressureHpa)
        DataFieldType.GPS_QNH_ALT -> gpsQnhAlt(location, hasGps, gpsStale, qnhHpa, geoidM, pressureHpa)
        DataFieldType.GROUND_SPEED -> groundSpeed(location, hasGps, gpsStale)
        DataFieldType.GPS_TRACK -> gpsTrack(location, hasGps, gpsStale)
        DataFieldType.V_SPEED -> vSpeed(hasGps, gpsStale, vSpeedFpm)
        DataFieldType.LAT -> lat(location, hasGps)
        DataFieldType.LON -> lon(location, hasGps)
        DataFieldType.UTC_TIME -> utcTime()
        DataFieldType.LOCAL_TIME -> localTime()
        DataFieldType.BATTERY -> FieldData("BATT", "$batteryPct", "%")
        DataFieldType.PRESSURE -> pressure(pressureHpa)
        DataFieldType.GPS_ACCURACY -> gpsAccuracy(location, hasGps)
        DataFieldType.OAT -> oat(temperatureC)
        DataFieldType.DENSITY_ALT -> densityAlt(pressureHpa, temperatureC, hasGps)
        DataFieldType.NONE -> FieldData("", "", "")
    }

    private fun gpsAltMsl(location: Location?, hasGps: Boolean, geoidM: Int): Int? {
        if (!hasGps || location == null) return null
        if (!location.hasAltitude()) return null
        // Reject altitude=0.0 from partial 2D fix with poor accuracy
        if (location.altitude == 0.0 && (!location.hasAccuracy() || location.accuracy > 20f)) return null
        val altMsl = location.altitude - geoidM
        return (altMsl * 3.28084).toInt()
    }

    private fun gpsAlt(location: Location?, hasGps: Boolean, stale: Boolean, geoidM: Int): FieldData {
        if (!hasGps) return FieldData("ALT GPS", "NoGPS", "ft")
        if (stale) return FieldData("ALT GPS", "- - -", "ft")
        val alt = gpsAltMsl(location, hasGps, geoidM) ?: return FieldData("ALT GPS", "- - -", "ft")
        return FieldData("ALT GPS", "$alt", "ft")
    }

    private fun qnhAlt(location: Location?, hasGps: Boolean, stale: Boolean,
                        qnhHpa: Int, geoidM: Int, pressureHpa: Float?): FieldData {
        // Barometric path
        if (pressureHpa != null && pressureHpa > 100f && qnhHpa > 0) {
            val ratio = pressureHpa / qnhHpa.toFloat()
            val altFt = (145366.45 * (1.0 - ratio.toDouble().pow(0.190284))).toInt()
            return FieldData("ALT QNH", "$altFt", "ft")
        }
        // GPS fallback
        if (!hasGps) return FieldData("ALT QNH", "NoGPS", "ft")
        if (stale) return FieldData("ALT QNH", "- - -", "ft")
        val gpsAlt = gpsAltMsl(location, hasGps, geoidM) ?: return FieldData("ALT QNH", "- - -", "ft")
        val correction = (qnhHpa - 1013.25f) * 30f
        return FieldData("ALT QNH", "${(gpsAlt + correction).toInt()}", "ft")
    }

    private fun gpsQnhAlt(location: Location?, hasGps: Boolean, stale: Boolean,
                           qnhHpa: Int, geoidM: Int, pressureHpa: Float?): FieldData {
        val gps = gpsAlt(location, hasGps, stale, geoidM)
        val qnh = qnhAlt(location, hasGps, stale, qnhHpa, geoidM, pressureHpa)
        return FieldData("GPS", gps.value, "ft", "QNH", qnh.value, isDual = true)
    }

    private fun groundSpeed(location: Location?, hasGps: Boolean, stale: Boolean): FieldData {
        if (!hasGps) return FieldData("GS", "NoGPS", "kt")
        if (stale || location == null || !location.hasSpeed()) return FieldData("GS", "- - -", "kt")
        // Reject zero speed from partial 2D fix with poor accuracy
        if (location.speed == 0f && (!location.hasAccuracy() || location.accuracy > 20f)) {
            return FieldData("GS", "- - -", "kt")
        }
        val kts = (location.speed / 0.514444f).toInt()
        return FieldData("GS", "$kts", "kt")
    }

    private fun gpsTrack(location: Location?, hasGps: Boolean, stale: Boolean): FieldData {
        if (!hasGps) return FieldData("TRK", "NoGPS", "°")
        if (stale || location == null || !location.hasBearing()) return FieldData("TRK", "- - -", "°")
        // Reject zero bearing from partial fix with poor accuracy
        if (location.bearing == 0f && location.speed == 0f && (!location.hasAccuracy() || location.accuracy > 20f)) {
            return FieldData("TRK", "- - -", "°")
        }
        val deg = location.bearing.toInt().let { if (it < 0) it + 360 else it }
        return FieldData("TRK", "%03d".format(deg), "°")
    }

    private fun vSpeed(hasGps: Boolean, stale: Boolean, vSpeedFpm: Float?): FieldData {
        if (!hasGps) return FieldData("VS", "NoGPS", "ft/m")
        if (stale || vSpeedFpm == null) return FieldData("VS", "- - -", "ft/m")
        val vs = vSpeedFpm.toInt()
        val sign = if (vs > 0) "+" else ""
        return FieldData("VS", "$sign$vs", "ft/m")
    }

    private fun lat(location: Location?, hasGps: Boolean): FieldData {
        if (!hasGps || location == null) return FieldData("LAT", "NoGPS")
        val lat = location.latitude
        val ns = if (lat >= 0) "N" else "S"
        val absLat = abs(lat)
        val deg = absLat.toInt()
        val min = (absLat - deg) * 60.0
        return FieldData("LAT", "$ns$deg°${"%.3f".format(min)}")
    }

    private fun lon(location: Location?, hasGps: Boolean): FieldData {
        if (!hasGps || location == null) return FieldData("LON", "NoGPS")
        val lon = location.longitude
        val ew = if (lon >= 0) "E" else "W"
        val absLon = abs(lon)
        val deg = absLon.toInt()
        val min = (absLon - deg) * 60.0
        return FieldData("LON", "$ew%03d°${"%.3f".format(min)}".format(deg))
    }

    private val utcFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val localFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun utcTime() = FieldData("UTC", utcFmt.format(Date()), "Z")
    private fun localTime() = FieldData("LCL", localFmt.format(Date()))

    private fun pressure(pressureHpa: Float?): FieldData {
        if (pressureHpa == null) return FieldData("QFE", "---", "hPa")
        return FieldData("QFE", "%.1f".format(pressureHpa), "hPa")
    }

    private fun gpsAccuracy(location: Location?, hasGps: Boolean): FieldData {
        if (!hasGps || location == null) return FieldData("GPS", "NoGPS")
        if (!location.hasAccuracy()) return FieldData("GPS", "---")
        val acc = location.accuracy
        val txt = when {
            acc <= 5f -> "Good"
            acc <= 15f -> "Ok"
            acc <= 30f -> "Poor"
            else -> "Low"
        }
        return FieldData("GPS", txt, "±${acc.toInt()}m")
    }

    private fun oat(temperatureC: Float?): FieldData {
        if (temperatureC == null) return FieldData("OAT", "---", "°C")
        return FieldData("OAT", "${temperatureC.toInt()}", "°C")
    }

    private fun densityAlt(pressureHpa: Float?, temperatureC: Float?, hasGps: Boolean): FieldData {
        if (pressureHpa == null || pressureHpa <= 100f || temperatureC == null) {
            return if (!hasGps) FieldData("DA", "NoGPS", "ft") else FieldData("DA", "---", "ft")
        }
        val paFt = 145366.45 * (1.0 - (pressureHpa / 1013.25).toDouble().pow(0.190284))
        val isaTemp = 15.0 - 0.001981 * paFt
        val da = (paFt + 120.0 * (temperatureC - isaTemp)).toInt()
        return FieldData("DA", "$da", "ft")
    }
}

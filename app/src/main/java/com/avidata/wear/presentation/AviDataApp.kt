package com.avidata.wear.presentation

import android.content.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.avidata.wear.data.*
import kotlinx.coroutines.delay

val AviYellow = Color(0xFFFFFF00)
val AviGreen = Color(0xFF00FF00)
val AviRed = Color(0xFFFF4444)
val AviGray = Color(0xFF888888)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AviDataApp(onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("avidata", Context.MODE_PRIVATE) }

    // Settings
    var qnhHpa by remember { mutableIntStateOf(prefs.getInt("qnhValue", 1013)) }
    val geoidM = remember { prefs.getInt("geoidHeight", 47) }
    val totalPages = remember { prefs.getInt("totalPages", 1).coerceIn(1, 4) }

    // Slot assignments (default page 1)
    val slots = remember {
        Array(4) { page ->
            Array(4) { quad ->
                val key = "slot_${page}_$quad"
                val default = when {
                    page == 0 && quad == 0 -> DataFieldType.GPS_ALT
                    page == 0 && quad == 1 -> DataFieldType.QNH_ALT
                    page == 0 && quad == 2 -> DataFieldType.GROUND_SPEED
                    page == 0 && quad == 3 -> DataFieldType.GPS_TRACK
                    else -> DataFieldType.NONE
                }
                val ordinal = prefs.getInt(key, default.ordinal)
                DataFieldType.entries.getOrElse(ordinal) { DataFieldType.NONE }
            }
        }
    }

    // Collect GPS state
    val location by GpsService.location.collectAsState()
    val hasGps by GpsService.hasGps.collectAsState()
    val gpsStale by GpsService.gpsStale.collectAsState()
    val vSpeedFpm by GpsService.vSpeedFpm.collectAsState()
    val pressureHpa by GpsService.pressureHpa.collectAsState()
    val temperatureC by GpsService.temperatureC.collectAsState()

    // Battery
    var batteryPct by remember { mutableIntStateOf(50) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // Tick every second for time updates
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); tick++ }
    }

    // Exit confirmation
    var showExitDialog by remember { mutableStateOf(false) }

    // QNH editor
    var showQnhEditor by remember { mutableStateOf(false) }

    if (showExitDialog) {
        ExitConfirmScreen(
            onConfirm = onExit,
            onCancel = { showExitDialog = false }
        )
        return
    }

    if (showQnhEditor) {
        QnhEditorScreen(
            initialQnh = qnhHpa,
            onSave = { newQnh ->
                qnhHpa = newQnh
                prefs.edit().putInt("qnhValue", newQnh).apply()
                showQnhEditor = false
            },
            onCancel = { showQnhEditor = false }
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { totalPages })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showExitDialog = true },
                    onDoubleTap = { offset ->
                        // Check if tapping on a QNH field
                        val page = pagerState.currentPage
                        val w = size.width; val h = size.height
                        val quadX = if (offset.x < w / 2) 0 else 1
                        val quadY = if (offset.y < h / 2) 0 else 1
                        val quad = quadY * 2 + quadX
                        val field = slots[page][quad]
                        if (field == DataFieldType.QNH_ALT || field == DataFieldType.GPS_QNH_ALT) {
                            showQnhEditor = true
                        }
                    }
                )
            }
    ) {
        // Force recomposition on tick
        tick.let { _ ->
            HorizontalPager(state = pagerState) { page ->
                DataPage(
                    slots = slots[page],
                    location = location,
                    hasGps = hasGps,
                    gpsStale = gpsStale,
                    qnhHpa = qnhHpa,
                    geoidM = geoidM,
                    vSpeedFpm = vSpeedFpm,
                    pressureHpa = pressureHpa,
                    temperatureC = temperatureC,
                    batteryPct = batteryPct
                )
            }
        }

        // Page indicator
        if (totalPages > 1) {
            HorizontalPageIndicator(
                pageIndicatorState = object : PageIndicatorState {
                    override val pageCount = totalPages
                    override val pageOffset = pagerState.currentPageOffsetFraction
                    override val selectedPage = pagerState.currentPage
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
fun DataPage(
    slots: Array<DataFieldType>,
    location: android.location.Location?,
    hasGps: Boolean,
    gpsStale: Boolean,
    qnhHpa: Int,
    geoidM: Int,
    vSpeedFpm: Float?,
    pressureHpa: Float?,
    temperatureC: Float?,
    batteryPct: Int
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 4 quadrants
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 28.dp)) {
            // Top row
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                QuadrantCell(slots[0], false, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, pressureHpa, temperatureC, batteryPct,
                    Modifier.weight(1f).fillMaxHeight())
                QuadrantCell(slots[1], false, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, pressureHpa, temperatureC, batteryPct,
                    Modifier.weight(1f).fillMaxHeight())
            }
            // Divider
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AviGray))
            // Bottom row
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                QuadrantCell(slots[2], true, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, pressureHpa, temperatureC, batteryPct,
                    Modifier.weight(1f).fillMaxHeight())
                QuadrantCell(slots[3], true, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, pressureHpa, temperatureC, batteryPct,
                    Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
fun QuadrantCell(
    fieldType: DataFieldType,
    isBottom: Boolean,
    location: android.location.Location?,
    hasGps: Boolean, gpsStale: Boolean,
    qnhHpa: Int, geoidM: Int,
    vSpeedFpm: Float?, pressureHpa: Float?, temperatureC: Float?,
    batteryPct: Int,
    modifier: Modifier
) {
    if (fieldType == DataFieldType.NONE) {
        Box(modifier = modifier)
        return
    }

    val data = AviDataProvider.getFieldData(
        fieldType, location, hasGps, gpsStale, qnhHpa, geoidM,
        vSpeedFpm, pressureHpa, temperatureC, batteryPct
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (data.isDual) {
            if (isBottom) Arrangement.Top else Arrangement.Bottom
        } else Arrangement.Center
    ) {
        if (data.isDual) {
            // Dual display: GPS + QNH stacked
            Text(data.label, color = AviYellow, fontSize = 9.sp)
            val gpsColor = when (data.value) {
                "NoGPS" -> AviRed; "- - -" -> AviGray; else -> Color.White
            }
            Text("${data.value}${data.unit}", color = gpsColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(data.label2 ?: "", color = AviYellow, fontSize = 9.sp)
            val qnhColor = when (data.value2) {
                "NoGPS" -> AviRed; "- - -" -> AviGray; else -> AviGreen
            }
            Text("${data.value2}${data.unit}", color = qnhColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        } else {
            // Standard field
            Text(data.label, color = AviYellow, fontSize = 9.sp)
            Spacer(Modifier.height(6.dp))
            val valueColor = when (data.value) {
                "NoGPS" -> AviRed; "- - -" -> AviGray; else -> Color.White
            }
            Text(data.value, color = valueColor, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (data.unit.isNotEmpty() && data.value != "NoGPS" && data.value != "- - -") {
                Text(data.unit, color = AviGray, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun ExitConfirmScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Quitter", color = AviYellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("AviData ?", color = AviYellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)) {
                    Text("Non", color = AviRed, fontSize = 16.sp)
                }
                Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)) {
                    Text("Oui", color = AviGreen, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun QnhEditorScreen(initialQnh: Int, onSave: (Int) -> Unit, onCancel: () -> Unit) {
    var qnh by remember { mutableIntStateOf(initialQnh) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("QNH (hPa)", color = AviYellow, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { qnh = (qnh - 1).coerceAtLeast(900) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                    modifier = Modifier.size(40.dp)) {
                    Text("-", color = AviRed, fontSize = 20.sp)
                }
                Text("$qnh", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { qnh = (qnh + 1).coerceAtMost(1100) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                    modifier = Modifier.size(40.dp)) {
                    Text("+", color = AviGreen, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("STD: 1013", color = AviGray, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)) {
                    Text("Annuler", fontSize = 12.sp)
                }
                Button(onClick = { onSave(qnh) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)) {
                    Text("OK", color = AviGreen, fontSize = 12.sp)
                }
            }
        }
    }
}

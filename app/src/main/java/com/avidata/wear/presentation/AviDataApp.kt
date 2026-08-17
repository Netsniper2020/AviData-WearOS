package com.avidata.wear.presentation

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

// Slot positions
data class SlotDef(val page: Int, val quad: Int, val label: String)

val ALL_SLOTS = listOf(
    SlotDef(0, 0, "Page 1 - Haut Gauche"),
    SlotDef(0, 1, "Page 1 - Haut Droite"),
    SlotDef(0, 2, "Page 1 - Bas Gauche"),
    SlotDef(0, 3, "Page 1 - Bas Droite"),
    SlotDef(1, 0, "Page 2 - Haut Gauche"),
    SlotDef(1, 1, "Page 2 - Haut Droite"),
    SlotDef(1, 2, "Page 2 - Bas Gauche"),
    SlotDef(1, 3, "Page 2 - Bas Droite"),
    SlotDef(2, 0, "Page 3 - Haut Gauche"),
    SlotDef(2, 1, "Page 3 - Haut Droite"),
    SlotDef(2, 2, "Page 3 - Bas Gauche"),
    SlotDef(2, 3, "Page 3 - Bas Droite"),
    SlotDef(3, 0, "Page 4 - Haut Gauche"),
    SlotDef(3, 1, "Page 4 - Haut Droite"),
    SlotDef(3, 2, "Page 4 - Bas Gauche"),
    SlotDef(3, 3, "Page 4 - Bas Droite"),
)

val FIELD_LABELS = mapOf(
    DataFieldType.NONE to "Aucun",
    DataFieldType.GPS_ALT to "Altitude GPS (ft)",
    DataFieldType.QNH_ALT to "Altitude QNH (ft)",
    DataFieldType.GPS_QNH_ALT to "Alt GPS + QNH (ft)",
    DataFieldType.GROUND_SPEED to "Vitesse sol (kt)",
    DataFieldType.GPS_TRACK to "Route GPS (°)",
    DataFieldType.V_SPEED to "Vario GPS (ft/m)",
    DataFieldType.V_SPEED_BARO to "Vario Baro (ft/m)",
    DataFieldType.LAT to "Latitude",
    DataFieldType.LON to "Longitude",
    DataFieldType.UTC_TIME to "Heure UTC",
    DataFieldType.LOCAL_TIME to "Heure locale",
    DataFieldType.BATTERY to "Batterie (%)",
    DataFieldType.PRESSURE to "Pression (hPa)",
    DataFieldType.GPS_ACCURACY to "Précision GPS",
    DataFieldType.OAT to "Température (°C)",
    DataFieldType.DENSITY_ALT to "Alt. densité (ft)",
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AviDataApp(onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("avidata", Context.MODE_PRIVATE) }

    var qnhHpa by remember { mutableIntStateOf(prefs.getInt("qnhValue", 1013)) }
    val geoidM = remember { prefs.getInt("geoidHeight", 47) }

    // Slot assignments
    val slots = remember {
        Array(4) { page ->
            Array(4) { quad ->
                val default = when {
                    page == 0 && quad == 0 -> DataFieldType.GPS_ALT
                    page == 0 && quad == 1 -> DataFieldType.QNH_ALT
                    page == 0 && quad == 2 -> DataFieldType.GROUND_SPEED
                    page == 0 && quad == 3 -> DataFieldType.GPS_TRACK
                    else -> DataFieldType.NONE
                }
                val ordinal = prefs.getInt("slot_${page}_$quad", default.ordinal)
                mutableStateOf(DataFieldType.entries.getOrElse(ordinal) { DataFieldType.NONE })
            }
        }
    }

    // Compute total pages
    val totalPages by remember {
        derivedStateOf {
            var max = 1
            for (p in 0..3) {
                for (q in 0..3) {
                    if (slots[p][q].value != DataFieldType.NONE && p + 1 > max) {
                        max = p + 1
                    }
                }
            }
            max
        }
    }

    // GPS state
    val location by GpsService.location.collectAsState()
    val hasGps by GpsService.hasGps.collectAsState()
    val gpsStale by GpsService.gpsStale.collectAsState()
    val vSpeedFpm by GpsService.vSpeedFpm.collectAsState()
    val vSpeedBaroFpm by GpsService.vSpeedBaroFpm.collectAsState()
    val pressureHpa by GpsService.pressureHpa.collectAsState()
    val temperatureC by GpsService.temperatureC.collectAsState()

    var batteryPct by remember { mutableIntStateOf(50) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // Tick every second
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    // Navigation state
    var screen by remember { mutableStateOf("main") } // main, menu, settings, picker, qnh, exit
    var editSlotPage by remember { mutableIntStateOf(0) }
    var editSlotQuad by remember { mutableIntStateOf(0) }

    when (screen) {
        "main" -> {
            val pagerState = rememberPagerState(pageCount = { totalPages })
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { screen = "menu" },
                            onDoubleTap = { offset ->
                                val page = pagerState.currentPage
                                val w = size.width; val h = size.height
                                val quadX = if (offset.x < w / 2) 0 else 1
                                val quadY = if (offset.y < h / 2) 0 else 1
                                val quad = quadY * 2 + quadX
                                val field = slots[page][quad].value
                                if (field == DataFieldType.QNH_ALT || field == DataFieldType.GPS_QNH_ALT) {
                                    screen = "qnh"
                                }
                            }
                        )
                    }
            ) {
                tick.let { _ ->
                    HorizontalPager(state = pagerState) { page ->
                        DataPage(
                            slots = Array(4) { slots[page][it].value },
                            location = location, hasGps = hasGps, gpsStale = gpsStale,
                            qnhHpa = qnhHpa, geoidM = geoidM, vSpeedFpm = vSpeedFpm,
                            vSpeedBaroFpm = vSpeedBaroFpm, pressureHpa = pressureHpa, temperatureC = temperatureC,
                            batteryPct = batteryPct
                        )
                    }
                }
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

        "menu" -> {
            MenuScreen(
                onSettings = { screen = "settings" },
                onExit = { screen = "exit" },
                onBack = { screen = "main" }
            )
        }

        "exit" -> {
            ExitConfirmScreen(
                onConfirm = onExit,
                onCancel = { screen = "main" }
            )
        }

        "qnh" -> {
            QnhEditorScreen(
                initialQnh = qnhHpa,
                onSave = { newQnh ->
                    qnhHpa = newQnh
                    prefs.edit().putInt("qnhValue", newQnh).apply()
                    screen = "main"
                },
                onCancel = { screen = "main" }
            )
        }

        "settings" -> {
            SettingsScreen(
                slots = slots,
                onEditSlot = { page, quad ->
                    editSlotPage = page
                    editSlotQuad = quad
                    screen = "picker"
                },
                onBack = { screen = "main" }
            )
        }

        "picker" -> {
            FieldPickerScreen(
                currentField = slots[editSlotPage][editSlotQuad].value,
                onSelect = { field ->
                    slots[editSlotPage][editSlotQuad].value = field
                    prefs.edit().putInt("slot_${editSlotPage}_$editSlotQuad", field.ordinal).apply()
                    screen = "settings"
                },
                onBack = { screen = "settings" }
            )
        }
    }
}

// ===== Menu screen =====
@Composable
fun MenuScreen(onSettings: () -> Unit, onExit: () -> Unit, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Text("AviData", color = AviYellow, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
            item {
                Chip(
                    onClick = onSettings,
                    label = { Text("Paramètres") },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF333333)),
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Chip(
                    onClick = onExit,
                    label = { Text("Quitter", color = AviRed) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF333333)),
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Chip(
                    onClick = onBack,
                    label = { Text("Retour", color = AviGray) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF222222)),
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }
        }
    }
}

// ===== Settings screen: list of slot positions =====
@Composable
fun SettingsScreen(
    slots: Array<Array<MutableState<DataFieldType>>>,
    onEditSlot: (Int, Int) -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text("Champs de données", color = AviYellow, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(ALL_SLOTS.size) { idx ->
                val slot = ALL_SLOTS[idx]
                val current = slots[slot.page][slot.quad].value
                val fieldLabel = FIELD_LABELS[current] ?: "?"
                Chip(
                    onClick = { onEditSlot(slot.page, slot.quad) },
                    label = { Text(slot.label, fontSize = 11.sp, maxLines = 1) },
                    secondaryLabel = { Text(fieldLabel, fontSize = 10.sp, color = AviGreen, maxLines = 1) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF333333)),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Chip(
                    onClick = onBack,
                    label = { Text("Retour") },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF222222)),
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }
        }
    }
}

// ===== Field picker: choose which data to show in a slot =====
@Composable
fun FieldPickerScreen(
    currentField: DataFieldType,
    onSelect: (DataFieldType) -> Unit,
    onBack: () -> Unit
) {
    val fields = DataFieldType.entries
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text("Choisir donnée", color = AviYellow, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(fields.size) { idx ->
                val field = fields[idx]
                val label = FIELD_LABELS[field] ?: field.name
                val isSelected = field == currentField
                Chip(
                    onClick = { onSelect(field) },
                    label = {
                        Text(label, fontSize = 12.sp,
                            color = if (isSelected) AviGreen else Color.White)
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (isSelected) Color(0xFF004400) else Color(0xFF333333)
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Chip(
                    onClick = onBack,
                    label = { Text("Annuler") },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF222222)),
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }
        }
    }
}

// ===== Data page with 4 quadrants =====
@Composable
fun DataPage(
    slots: Array<DataFieldType>,
    location: android.location.Location?,
    hasGps: Boolean, gpsStale: Boolean,
    qnhHpa: Int, geoidM: Int,
    vSpeedFpm: Float?, vSpeedBaroFpm: Float?,
    pressureHpa: Float?, temperatureC: Float?,
    batteryPct: Int
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 28.dp)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                QuadrantCell(slots[0], false, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, vSpeedBaroFpm, pressureHpa, temperatureC, batteryPct, Modifier.weight(1f).fillMaxHeight())
                QuadrantCell(slots[1], false, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, vSpeedBaroFpm, pressureHpa, temperatureC, batteryPct, Modifier.weight(1f).fillMaxHeight())
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AviGray))
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                QuadrantCell(slots[2], true, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, vSpeedBaroFpm, pressureHpa, temperatureC, batteryPct, Modifier.weight(1f).fillMaxHeight())
                QuadrantCell(slots[3], true, location, hasGps, gpsStale, qnhHpa, geoidM, vSpeedFpm, vSpeedBaroFpm, pressureHpa, temperatureC, batteryPct, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
fun QuadrantCell(
    fieldType: DataFieldType, isBottom: Boolean,
    location: android.location.Location?, hasGps: Boolean, gpsStale: Boolean,
    qnhHpa: Int, geoidM: Int, vSpeedFpm: Float?, vSpeedBaroFpm: Float?,
    pressureHpa: Float?, temperatureC: Float?, batteryPct: Int, modifier: Modifier
) {
    if (fieldType == DataFieldType.NONE) { Box(modifier = modifier); return }

    val data = AviDataProvider.getFieldData(
        fieldType, location, hasGps, gpsStale, qnhHpa, geoidM,
        vSpeedFpm, vSpeedBaroFpm, pressureHpa, temperatureC, batteryPct
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (data.isDual) {
            if (isBottom) Arrangement.Top else Arrangement.Bottom
        } else Arrangement.Center
    ) {
        if (data.isDual) {
            Text(data.label, color = AviYellow, fontSize = 9.sp)
            val gpsColor = when (data.value) { "NoGPS" -> AviRed; "- - -" -> AviGray; else -> Color.White }
            Text("${data.value}${data.unit}", color = gpsColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(data.label2 ?: "", color = AviYellow, fontSize = 9.sp)
            val qnhColor = when (data.value2) { "NoGPS" -> AviRed; "- - -" -> AviGray; else -> AviGreen }
            Text("${data.value2}${data.unit}", color = qnhColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(data.label, color = AviYellow, fontSize = 9.sp)
            Spacer(Modifier.height(6.dp))
            val valueColor = when (data.value) { "NoGPS" -> AviRed; "- - -" -> AviGray; else -> Color.White }
            Text(data.value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (data.unit.isNotEmpty() && data.value != "NoGPS" && data.value != "- - -") {
                Text(data.unit, color = AviGray, fontSize = 10.sp)
            }
        }
    }
}

// ===== Exit confirm =====
@Composable
fun ExitConfirmScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
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

// ===== QNH editor =====
@Composable
fun QnhEditorScreen(initialQnh: Int, onSave: (Int) -> Unit, onCancel: () -> Unit) {
    var qnh by remember { mutableIntStateOf(initialQnh) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("QNH (hPa)", color = AviYellow, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { qnh = (qnh - 1).coerceAtLeast(900) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray), modifier = Modifier.size(40.dp)) {
                    Text("-", color = AviRed, fontSize = 20.sp)
                }
                Text("$qnh", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { qnh = (qnh + 1).coerceAtMost(1100) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray), modifier = Modifier.size(40.dp)) {
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

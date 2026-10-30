package dev.roque.pocketpad

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B0D12)
private val Panel = Color.White.copy(alpha = 0.07f)
private val Line = Color.White.copy(alpha = 0.16f)
private val Ink = Color(0xFFE7ECF5)
private val Accent = Color(0xFF2EE6A8)
private val Danger = Color(0xFFFF5B5B)

class MainActivity : ComponentActivity() {
    private val link = PadLink()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg,
                    surface = Bg,
                    onBackground = Ink,
                    onSurface = Ink,
                ),
            ) {
                PocketPadApp(link)
            }
        }
    }

    override fun onDestroy() {
        link.disconnect()
        super.onDestroy()
    }
}

@Composable
fun PocketPadApp(link: PadLink) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pocketpad", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty()) }
    var port by remember { mutableStateOf(prefs.getString("port", "9001").orEmpty()) }
    var pin by remember { mutableStateOf(prefs.getString("pin", "").orEmpty()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var usbMode by remember { mutableStateOf(prefs.getBoolean("usb", false)) }
    var wantConnected by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val input = remember { PadInput() }
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }

    LaunchedEffect(Unit) {
        link.onRumble = { ms, amp ->
            vibrator?.let { device ->
                runCatching {
                    device.vibrate(
                        VibrationEffect.createOneShot(
                            ms.toLong().coerceIn(30L, 1500L),
                            amp.coerceIn(1, 255),
                        ),
                    )
                }
            }
        }
    }

    // Reconexión automática: si la sesión cae sola, reintenta hasta 40 veces.
    LaunchedEffect(link.connected) {
        if (link.connected) {
            wantConnected = true
            reconnecting = false
            return@LaunchedEffect
        }
        if (!wantConnected) return@LaunchedEffect
        reconnecting = true
        repeat(40) {
            delay(1200)
            if (link.connected) return@LaunchedEffect
            val savedHost = prefs.getString("host", null) ?: return@LaunchedEffect
            val savedPort = prefs.getString("port", "9001")?.toIntOrNull() ?: 9001
            val error = link.connect(
                savedHost,
                savedPort,
                deviceName(),
                prefs.getBoolean("usb", false),
                prefs.getString("pin", "").orEmpty(),
            )
            if (error == null && link.connected) return@LaunchedEffect
            message = "Reconectando… (${it + 1}/40)"
        }
        reconnecting = false
        message = "No se pudo reconectar. Pulse Conectar."
    }

    LaunchedEffect(link.connected) {
        if (link.connected) link.startStreaming(input)
    }

    if (link.connected) {
        ControllerScreen(
            link = link,
            input = input,
            onExit = {
                wantConnected = false
                input.reset()
                link.disconnect()
                link.onRumble = null
            },
        )
    } else {
        SetupScreen(
            host = host,
            port = port,
            pin = pin,
            message = message.ifEmpty { link.status },
            busy = busy,
            searching = searching,
            usbMode = usbMode,
            onHost = { host = it },
            onPort = { port = it },
            onPin = { pin = it },
            onUsb = {
                usbMode = it
                if (it) {
                    host = "127.0.0.1"
                    port = "9001"
                }
            },
            onDiscover = {
                scope.launch {
                    searching = true
                    message = "Buscando en la red local…"
                    val found = link.discover(port.toIntOrNull() ?: 9001, pin)
                    searching = false
                    if (found != null) {
                        host = found
                        message = "PC encontrada: $found"
                    } else {
                        message = "No se encontró ninguna PC. Escriba la IP a mano."
                    }
                }
            },
            onConnect = {
                val cleanHost = host.trim()
                val cleanPort = port.toIntOrNull()
                if (cleanHost.isEmpty() || cleanPort == null) {
                    message = "IP o puerto inválidos"
                } else {
                    scope.launch {
                        busy = true
                        val error = link.connect(cleanHost, cleanPort, deviceName(), usbMode, pin.trim())
                        busy = false
                        if (error == null) {
                            prefs.edit()
                                .putString("host", cleanHost)
                                .putString("port", port)
                                .putBoolean("usb", usbMode)
                                .putString("pin", pin.trim())
                                .apply()
                            message = ""
                        } else {
                            message = error
                        }
                    }
                }
            },
        )
    }
}

private fun deviceName(): String =
    (Build.MANUFACTURER + " " + Build.MODEL).trim().take(32).ifEmpty { "android" }

@Composable
private fun SetupScreen(
    host: String,
    port: String,
    pin: String,
    message: String,
    busy: Boolean,
    searching: Boolean,
    usbMode: Boolean,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onPin: (String) -> Unit,
    onUsb: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
) {
    var showCredits by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "POCKETPAD",
                color = Accent,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "mando DualShock virtual por WiFi o USB",
                color = Ink.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text("por Roque", color = Accent.copy(alpha = 0.55f), fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHost,
                    label = { Text("IP de la PC", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f).height(50.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = onPort,
                    label = { Text("Puerto", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(92.dp).height(50.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = onPin,
                label = { Text("PIN de la sesión (opcional)", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = usbMode, onCheckedChange = onUsb)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Por USB (adb reverse)", color = Ink, fontSize = 12.sp)
                    Text(
                        "sin WiFi; la PC debe ejecutar adb reverse",
                        color = Ink.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDiscover,
                    enabled = !busy && !searching && !usbMode,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { Text(if (searching) "Buscando…" else "Buscar en la red", fontSize = 13.sp) }

                Button(
                    onClick = onConnect,
                    enabled = !busy && !searching,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { Text(if (busy) "Conectando…" else "Conectar", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = Ink.copy(alpha = 0.65f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Linux por ahora · Windows y macOS en camino",
                color = Ink.copy(alpha = 0.4f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "v0.5 · hecho por Roque",
                color = Ink.copy(alpha = 0.35f),
                fontSize = 10.sp,
            )
            Text(
                "créditos",
                color = Accent.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable { showCredits = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (showCredits) {
            CreditsDialog(onClose = { showCredits = false })
        }
    }
}

@Composable
private fun CreditsDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("cerrar") }
        },
        title = { Text("PocketPad 0.2", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Mandos DualShock 4 virtuales para jugar desde el teléfono.", fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Credit("Idea, diseño y pruebas", "Roque")
                Credit("Servidor", "Python + uinput (evdev)")
                Credit("App Android", "Kotlin + Jetpack Compose")
                Credit("Cliente web", "HTML + WebSocket")
                Credit("Transporte", "UDP, TCP y WebSocket")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Marca DualShock y PlayStation son de Sony; este proyecto no está afiliado.",
                    color = Ink.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                )
            }
        },
        containerColor = Color(0xFF141821),
        titleContentColor = Ink,
        textContentColor = Ink.copy(alpha = 0.8f),
    )
}

@Composable
private fun Credit(role: String, name: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(role, color = Ink.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(name, color = Ink, fontSize = 10.sp)
    }
}

@Composable
private fun ControllerScreen(link: PadLink, input: PadInput, onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pocketpad", Context.MODE_PRIVATE) }
    val layout = remember { PadLayout(prefs) }
    var editing by remember { mutableStateOf(false) }
    val gyro = remember { Gyro(context, input) }
    var showProfiles by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var menuShift by remember { mutableStateOf(Offset.Zero) }
    var gearShift by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) { onDispose { gyro.stop() } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        val w = maxWidth
        val h = maxHeight
        val shoulderW = w * 0.18f
        val shoulderH = (h * 0.12f).coerceIn(38.dp, 66.dp)
        val stickSize = minOf(w * 0.24f, h * 0.42f)
        val dpadSize = minOf(w * 0.15f, h * 0.42f)
        val faceSize = minOf(w * 0.17f, h * 0.46f)

        PadSlot(layout.item("l2", 0.015f, 0.025f), shoulderW, shoulderH, w, h, editing) {
            PadButton("L2", input, "l2", Modifier.fillMaxSize(), RoundedCornerShape(16.dp), 15.sp, enabled = !editing)
        }
        PadSlot(layout.item("l1", 0.015f, 0.18f), shoulderW, shoulderH, w, h, editing) {
            PadButton("L1", input, "l1", Modifier.fillMaxSize(), RoundedCornerShape(16.dp), 15.sp, enabled = !editing)
        }
        PadSlot(layout.item("r2", 0.985f, 0.025f), shoulderW, shoulderH, w, h, editing) {
            PadButton("R2", input, "r2", Modifier.fillMaxSize(), RoundedCornerShape(16.dp), 15.sp, enabled = !editing)
        }
        PadSlot(layout.item("r1", 0.985f, 0.18f), shoulderW, shoulderH, w, h, editing) {
            PadButton("R1", input, "r1", Modifier.fillMaxSize(), RoundedCornerShape(16.dp), 15.sp, enabled = !editing)
        }

        PadSlot(layout.item("stickL", 0.018f, 0.95f), stickSize, stickSize, w, h, editing) {
            StickPad(input.left, input, "l3", "L3", Modifier.fillMaxSize(), enabled = !editing)
        }
        PadSlot(layout.item("stickR", 0.982f, 0.95f), stickSize, stickSize, w, h, editing) {
            StickPad(input.right, input, "r3", "R3", Modifier.fillMaxSize(), enabled = !editing)
        }

        PadSlot(layout.item("dpad", 0.43f, 0.93f), dpadSize, dpadSize, w, h, editing) {
            Dpad(input, Modifier.fillMaxSize(), enabled = !editing)
        }
        PadSlot(layout.item("face", 0.65f, 0.94f), faceSize, faceSize, w, h, editing) {
            FaceButtons(input, Modifier.fillMaxSize(), enabled = !editing)
        }

        PadSlot(
            layout.item("touchpad", 0.34f, 0.24f),
            w * 0.30f,
            (h * 0.16f).coerceIn(56.dp, 130.dp),
            w,
            h,
            editing,
        ) {
            TouchPadArea(link, Modifier.fillMaxSize(), enabled = !editing)
        }

        PadSlot(layout.item("share", 0.40f, 0.02f), 62.dp, 36.dp, w, h, editing) {
            PadButton("SHARE", input, "share", Modifier.fillMaxSize(), RoundedCornerShape(50), 9.sp, enabled = !editing)
        }
        PadSlot(layout.item("ps", 0.50f, 0.02f), 42.dp, 42.dp, w, h, editing) {
            PadButton("PS", input, "ps", Modifier.fillMaxSize(), CircleShape, 10.sp, enabled = !editing)
        }
        PadSlot(layout.item("options", 0.60f, 0.02f), 62.dp, 36.dp, w, h, editing) {
            PadButton("START", input, "options", Modifier.fillMaxSize(), RoundedCornerShape(50), 9.sp, enabled = !editing)
        }

        val density = LocalDensity.current
        val menuMaxX = with(density) { (w / 2 - 130.dp).toPx() }.coerceAtLeast(0f)
        val menuMaxY = with(density) { (h / 2 - 100.dp).toPx() }.coerceAtLeast(0f)

        if (editing) {
            EditBar(
                modifier = Modifier.align(Alignment.TopCenter),
                profile = layout.profile,
                onDone = {
                    layout.save()
                    editing = false
                },
            )
        } else if (showMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { showMenu = false } },
            )
            SettingsPanel(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(menuShift.x.roundToInt(), menuShift.y.roundToInt()) },
                profile = layout.profile,
                slot = link.slot,
                host = link.host.orEmpty(),
                connected = link.connected,
                gyro = gyro,
                onDrag = { drag ->
                    menuShift = Offset(
                        (menuShift.x + drag.x).coerceIn(-menuMaxX, menuMaxX),
                        (menuShift.y + drag.y).coerceIn(-menuMaxY, menuMaxY),
                    )
                },
                onProfiles = {
                    layout.save()
                    showMenu = false
                    showProfiles = true
                },
                onEdit = {
                    showMenu = false
                    editing = true
                },
                onReset = { layout.reset() },
                onExit = onExit,
                onClose = { showMenu = false },
            )
        } else {
            SettingsButton(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(gearShift.x.roundToInt(), gearShift.y.roundToInt()) },
                connected = link.connected,
                onDrag = { drag ->
                    val maxX = with(density) { (w / 2 - 26.dp).toPx() }.coerceAtLeast(0f)
                    val maxY = with(density) { (h / 2 - 26.dp).toPx() }.coerceAtLeast(0f)
                    gearShift = Offset(
                        (gearShift.x + drag.x).coerceIn(-maxX, maxX),
                        (gearShift.y + drag.y).coerceIn(-maxY, maxY),
                    )
                },
                onClick = {
                    menuShift = Offset.Zero
                    showMenu = true
                },
            )
        }

        if (showProfiles) {
            ProfilesDialog(
                layout = layout,
                onClose = { showProfiles = false },
            )
        }
    }
}

/** Contenedor colocable: arrastra para mover y pellizca para escalar. */
@Composable
private fun PadSlot(
    item: PadItem,
    baseWidth: Dp,
    baseHeight: Dp,
    containerWidth: Dp,
    containerHeight: Dp,
    editing: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val width = baseWidth * item.scale
    val height = baseHeight * item.scale
    val maxX = (containerWidth - width).coerceAtLeast(0.dp)
    val maxY = (containerHeight - height).coerceAtLeast(0.dp)

    val dragModifier = if (editing) {
        Modifier
            .border(1.dp, Accent.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    item.scale = (item.scale * zoom).coerceIn(0.6f, 2.2f)
                    val spanX = maxX.toPx()
                    val spanY = maxY.toPx()
                    if (spanX > 0f) item.fx = ((item.fx * spanX + pan.x) / spanX).coerceIn(0f, 1f)
                    if (spanY > 0f) item.fy = ((item.fy * spanY + pan.y) / spanY).coerceIn(0f, 1f)
                }
            }
    } else {
        Modifier
    }

    Box(
        Modifier
            .offset { IntOffset((maxX * item.fx).roundToPx(), (maxY * item.fy).roundToPx()) }
            .size(width, height)
            .then(dragModifier),
        content = content,
    )
}

/** Touchpad del DS4: hasta dos dedos, envía posiciones normalizadas. */
@Composable
private fun TouchPadArea(link: PadLink, modifier: Modifier, enabled: Boolean) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    var active = 0
                    while (true) {
                        val pressed = awaitPointerEvent().changes.filter { it.pressed }.take(2)
                        if (pressed.isNotEmpty()) {
                            link.sendTouch(
                                pressed.map { change ->
                                    (change.position.x / size.width).coerceIn(0f, 1f) to
                                        (change.position.y / size.height).coerceIn(0f, 1f)
                                },
                            )
                            active = pressed.size
                        } else if (active > 0) {
                            link.sendTouch(emptyList())
                            active = 0
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("touchpad", color = Ink.copy(alpha = 0.16f), fontSize = 10.sp)
    }
}

/** Botón de ajustes: abre el panel central de opciones. */
@Composable
private fun SettingsButton(
    modifier: Modifier,
    connected: Boolean,
    onDrag: (Offset) -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(46.dp)
            .pointerInput(Unit) { detectDragGestures { _, drag -> onDrag(drag) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Line, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙", color = Ink.copy(alpha = 0.85f), fontSize = 22.sp)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(9.dp)
                .clip(CircleShape)
                .background(if (connected) Accent else Danger),
        )
    }
}

/** Panel de ajustes: centrado y arrastrable por la franja superior. */
@Composable
private fun SettingsPanel(
    modifier: Modifier,
    profile: String,
    slot: Int,
    host: String,
    connected: Boolean,
    gyro: Gyro,
    onDrag: (Offset) -> Unit,
    onProfiles: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141821).copy(alpha = 0.97f))
            .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectDragGestures { _, drag -> onDrag(drag) } }
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("⠿", color = Ink.copy(alpha = 0.4f), fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (connected) Accent else Danger),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (host.isEmpty()) "mando ${slot + 1}" else "mando ${slot + 1} · $host",
                color = Ink.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuChip(profile, Accent.copy(alpha = 0.9f)) { onProfiles() }
            if (gyro.available) {
                MenuChip(
                    "giro",
                    if (gyro.enabled) Accent else Ink.copy(alpha = 0.7f),
                    bold = gyro.enabled,
                ) { gyro.toggle() }
            }
            MenuChip("editar", Ink.copy(alpha = 0.8f)) { onEdit() }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuChip("restablecer", Ink.copy(alpha = 0.8f)) { onReset() }
            MenuChip("salir", Danger.copy(alpha = 0.9f)) { onExit() }
            MenuChip("cerrar", Ink.copy(alpha = 0.5f)) { onClose() }
        }
    }
}

@Composable
private fun MenuChip(label: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 12.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun EditBar(
    modifier: Modifier,
    profile: String,
    onDone: () -> Unit,
) {
    Row(
        modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(profile, color = Accent.copy(alpha = 0.85f), fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        Text("arrastra · pellizca", color = Ink.copy(alpha = 0.75f), fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            "listo",
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable { onDone() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProfilesDialog(layout: PadLayout, onClose: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("cerrar") } },
        title = { Text("Perfiles de mando", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                layout.profileNames.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (name == layout.profile) "● $name" else name,
                            color = if (name == layout.profile) Accent else Ink,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { layout.switchTo(name) }
                                .padding(vertical = 6.dp),
                        )
                        if (name != PadLayout.DEFAULT) {
                            Text(
                                "borrar",
                                color = Ink.copy(alpha = 0.45f),
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .clickable { layout.delete(name) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = ""
                        },
                        label = { Text("nuevo perfil") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (!layout.create(draft)) error = "nombre vacío o repetido"
                        else draft = ""
                    }) { Text("crear") }
                }
                if (error.isNotEmpty()) {
                    Text(error, color = Danger, fontSize = 10.sp)
                }
            }
        },
        containerColor = Color(0xFF141821),
        titleContentColor = Ink,
        textContentColor = Ink.copy(alpha = 0.8f),
    )
}

@Composable
private fun PadButton(
    label: String,
    input: PadInput,
    name: String,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    fontSize: TextUnit = 14.sp,
    textColor: Color = Ink,
    enabled: Boolean = true,
) {
    val pressed = input.isPressed(name)
    val view = LocalView.current
    val gestures = if (enabled) {
        Modifier.pointerInput(name) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                input.press(name)
                waitForUpOrCancellation()
                input.release(name)
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier
            .clip(shape)
            .background(if (pressed) Accent.copy(alpha = 0.35f) else Panel)
            .border(1.dp, Line, shape)
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StickPad(
    stick: Stick,
    input: PadInput,
    clickName: String,
    tag: String,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    var nub by remember { mutableStateOf(Offset.Zero) }
    var clicking by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.03f),
                    ),
                ),
            )
            .border(1.dp, Line, CircleShape),
    ) {
        val diameter = minOf(maxWidth, maxHeight)
        val gestures = if (enabled) {
            Modifier.pointerInput(clickName) {
                val radius = size.width / 2f
                val center = Offset(radius, radius)
                val slop = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startedAt = SystemClock.uptimeMillis()
                    if (clickName == "r3") input.stickRightHeld = true
                    var moved = false
                    var finger = down.position
                    nub = moveStick(stick, finger, center, radius)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        finger = change.position
                        if (!moved && (finger - down.position).getDistance() > slop) {
                            moved = true
                        }
                        if (moved) nub = moveStick(stick, finger, center, radius)
                        change.consume()
                        if (!change.pressed) break
                    }
                    val tapped = !moved && SystemClock.uptimeMillis() - startedAt < 400
                    if (clickName == "r3") input.stickRightHeld = false
                    stick.reset()
                    nub = Offset.Zero
                    if (tapped) {
                        // Solo un toque seco pulsa L3/R3; si hay arrastre, nunca.
                        clicking = true
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        input.press(clickName)
                        scope.launch {
                            delay(90)
                            input.release(clickName)
                            clicking = false
                        }
                    }
                }
            }
        } else {
            Modifier
        }

        Box(
            Modifier
                .fillMaxSize()
                .then(gestures),
        )

        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset((nub.x * diameter.toPx()).roundToInt(), (nub.y * diameter.toPx()).roundToInt()) }
                .size(diameter * 0.44f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            if (clicking) Accent else Color(0xFF4A5364),
                            Color(0xFF1A1F28),
                        ),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        )
        Text(
            tag,
            color = Ink.copy(alpha = 0.35f),
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

/** Devuelve el desplazamiento visual del nudillo como fracción del diámetro. */
private fun moveStick(stick: Stick, position: Offset, center: Offset, radius: Float): Offset {
    var dx = (position.x - center.x) / radius
    var dy = (position.y - center.y) / radius
    val length = hypot(dx, dy)
    if (length > 1f) {
        dx /= length
        dy /= length
    }
    if (hypot(dx, dy) < 0.12f) {
        dx = 0f
        dy = 0f
    }
    stick.set(dx, dy)
    return Offset(dx * 0.28f, dy * 0.28f)
}

@Composable
private fun Dpad(input: PadInput, modifier: Modifier, enabled: Boolean) {
    BoxWithConstraints(modifier) {
        val cell = minOf(maxWidth, maxHeight) * 0.37f
        PadButton("▲", input, "up", Modifier.align(Alignment.TopCenter).size(cell), RoundedCornerShape(12.dp), 16.sp, enabled = enabled)
        PadButton("◀", input, "left", Modifier.align(Alignment.CenterStart).size(cell), RoundedCornerShape(12.dp), 16.sp, enabled = enabled)
        PadButton("▶", input, "right", Modifier.align(Alignment.CenterEnd).size(cell), RoundedCornerShape(12.dp), 16.sp, enabled = enabled)
        PadButton("▼", input, "down", Modifier.align(Alignment.BottomCenter).size(cell), RoundedCornerShape(12.dp), 16.sp, enabled = enabled)
    }
}

@Composable
private fun FaceButtons(input: PadInput, modifier: Modifier, enabled: Boolean) {
    BoxWithConstraints(modifier) {
        val cell = minOf(maxWidth, maxHeight) * 0.39f
        PadButton("△", input, "triangle", Modifier.align(Alignment.TopCenter).size(cell), CircleShape, 18.sp, Color(0xFF7EF0B0), enabled)
        PadButton("□", input, "square", Modifier.align(Alignment.CenterStart).size(cell), CircleShape, 18.sp, Color(0xFFFF8FC4), enabled)
        PadButton("○", input, "circle", Modifier.align(Alignment.CenterEnd).size(cell), CircleShape, 18.sp, Color(0xFFFF7A7A), enabled)
        PadButton("✕", input, "cross", Modifier.align(Alignment.BottomCenter).size(cell), CircleShape, 18.sp, Color(0xFF7AB8FF), enabled)
    }
}

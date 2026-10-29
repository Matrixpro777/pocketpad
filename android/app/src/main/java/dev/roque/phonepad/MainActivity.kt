package dev.roque.phonepad

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
                PhonepadApp(link)
            }
        }
    }

    override fun onDestroy() {
        link.disconnect()
        super.onDestroy()
    }
}

@Composable
fun PhonepadApp(link: PadLink) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("phonepad", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty()) }
    var port by remember { mutableStateOf(prefs.getString("port", "9001").orEmpty()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var usbMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val input = remember { PadInput() }

    LaunchedEffect(link.connected) {
        if (link.connected) link.startStreaming(input)
    }

    if (link.connected) {
        ControllerScreen(
            link = link,
            input = input,
            onExit = {
                input.reset()
                link.disconnect()
            },
        )
    } else {
        SetupScreen(
            host = host,
            port = port,
            message = message.ifEmpty { link.status },
            busy = busy,
            searching = searching,
            usbMode = usbMode,
            onHost = { host = it },
            onPort = { port = it },
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
                    val found = link.discover(port.toIntOrNull() ?: 9001)
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
                        val error = link.connect(cleanHost, cleanPort, deviceName(), usbMode)
                        busy = false
                        if (error == null) {
                            prefs.edit().putString("host", cleanHost).putString("port", port).apply()
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
    message: String,
    busy: Boolean,
    searching: Boolean,
    usbMode: Boolean,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
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
                .widthIn(max = 520.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "PHONEPAD",
                color = Accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "mando DualShock virtual por WiFi o USB",
                color = Ink.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text("por Roque", color = Accent.copy(alpha = 0.55f), fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(22.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHost,
                    label = { Text("IP de la PC") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = onPort,
                    label = { Text("Puerto") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = usbMode, onCheckedChange = onUsb)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Por USB (adb reverse)", color = Ink, fontSize = 13.sp)
                    Text(
                        "sin WiFi; la PC debe ejecutar adb reverse",
                        color = Ink.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDiscover,
                    enabled = !busy && !searching && !usbMode,
                    modifier = Modifier.weight(1f),
                ) { Text(if (searching) "Buscando…" else "Buscar en la red") }

                Button(
                    onClick = onConnect,
                    enabled = !busy && !searching,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "Conectando…" else "Conectar") }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                message,
                color = Ink.copy(alpha = 0.65f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(26.dp))
            Text(
                "v0.2 · hecho por Roque",
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
        title = { Text("Phonepad 0.2", fontWeight = FontWeight.Bold) },
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
    val prefs = remember { context.getSharedPreferences("phonepad", Context.MODE_PRIVATE) }
    val layout = remember { PadLayout.load(prefs) }
    var editing by remember { mutableStateOf(false) }

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

        PadSlot(layout.item("share", 0.40f, 0.02f), 62.dp, 36.dp, w, h, editing) {
            PadButton("SHARE", input, "share", Modifier.fillMaxSize(), RoundedCornerShape(50), 9.sp, enabled = !editing)
        }
        PadSlot(layout.item("ps", 0.50f, 0.02f), 42.dp, 42.dp, w, h, editing) {
            PadButton("PS", input, "ps", Modifier.fillMaxSize(), CircleShape, 10.sp, enabled = !editing)
        }
        PadSlot(layout.item("options", 0.60f, 0.02f), 62.dp, 36.dp, w, h, editing) {
            PadButton("START", input, "options", Modifier.fillMaxSize(), RoundedCornerShape(50), 9.sp, enabled = !editing)
        }

        if (editing) {
            EditBar(
                modifier = Modifier.align(Alignment.TopCenter),
                onReset = {
                    layout.reset()
                    layout.save(prefs)
                },
                onDone = {
                    layout.save(prefs)
                    editing = false
                },
            )
        } else {
            Hud(
                modifier = Modifier.align(Alignment.TopCenter),
                link = link,
                onEdit = { editing = true },
                onExit = onExit,
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

@Composable
private fun Hud(modifier: Modifier, link: PadLink, onEdit: () -> Unit, onExit: () -> Unit) {
    Row(
        modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Line, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (link.connected) Accent else Danger),
        )
        Spacer(Modifier.width(8.dp))
        Text("mando ${link.slot + 1} · ${link.host.orEmpty()}", color = Ink, fontSize = 11.sp)
        Spacer(Modifier.width(14.dp))
        Text(
            "editar",
            color = Ink.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .clickable { onEdit() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            "salir",
            color = Ink.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .clickable { onExit() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EditBar(modifier: Modifier, onReset: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("arrastra · pellizca", color = Ink.copy(alpha = 0.75f), fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            "restablecer",
            color = Ink.copy(alpha = 0.75f),
            fontSize = 11.sp,
            modifier = Modifier
                .clickable { onReset() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
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
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isClick = (down.position - center).getDistance() < radius * 0.18f
                    if (isClick) {
                        clicking = true
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        input.press(clickName)
                    } else {
                        nub = moveStick(stick, down.position, center, radius)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (!isClick) nub = moveStick(stick, change.position, center, radius)
                        change.consume()
                    }
                    if (isClick) {
                        clicking = false
                        input.release(clickName)
                    } else {
                        stick.reset()
                        nub = Offset.Zero
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

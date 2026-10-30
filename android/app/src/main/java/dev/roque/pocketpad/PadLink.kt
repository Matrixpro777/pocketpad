package dev.roque.pocketpad

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** Orden canónico de bits; debe coincidir con pocketpad/protocol.py. */
object Buttons {
    val ORDER = listOf(
        "cross", "circle", "square", "triangle",
        "l1", "r1", "l2", "r2",
        "l3", "r3", "share", "options", "ps",
        "up", "down", "left", "right",
    )

    private val bits = ORDER.withIndex().associate { (i, name) -> name to (1 shl i) }

    fun bit(name: String): Int = bits[name] ?: 0
}

/** Eje analógico con lectura sin recomposición desde el hilo de red. */
class Stick {
    @Volatile var x = 0f
    @Volatile var y = 0f

    /**
     * Zona muerta radial (0.08) y curva exponencial: cerca del centro el mando
     * responde más fino, y fuera de la zona muerta el recorrido llega a 1.0.
     */
    fun set(nx: Float, ny: Float) {
        val magnitude = hypot(nx, ny)
        if (magnitude <= DEADZONE) {
            x = 0f
            y = 0f
            return
        }
        val norm = ((magnitude - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        val scale = norm.pow(CURVE) / magnitude
        x = (nx * scale).coerceIn(-1f, 1f)
        y = (ny * scale).coerceIn(-1f, 1f)
    }

    fun reset() = set(0f, 0f)

    private companion object {
        const val DEADZONE = 0.08f
        const val CURVE = 1.45f
    }
}

class PadInput {
    var mask by mutableIntStateOf(0)
        private set
    val left = Stick()
    val right = Stick()

    /** El dedo está sobre el stick derecho: el giroscopio no debe moverlo. */
    @Volatile
    var stickRightHeld = false

    fun press(name: String) {
        mask = mask or Buttons.bit(name)
    }

    fun release(name: String) {
        mask = mask and Buttons.bit(name).inv()
    }

    fun isPressed(name: String): Boolean = mask and Buttons.bit(name) != 0

    fun axes(): FloatArray = floatArrayOf(
        left.x, left.y, right.x, right.y,
        if (isPressed("l2")) 1f else 0f,
        if (isPressed("r2")) 1f else 0f,
    )

    fun reset() {
        mask = 0
        left.reset()
        right.reset()
    }
}

/**
 * Conexión con el servidor PocketPad. Una sesión = un mando virtual.
 * WiFi usa UDP; por USB se usa TCP (localhost vía `adb reverse`).
 */
class PadLink {
    var status by mutableStateOf("Sin conexión")
        private set
    var connected by mutableStateOf(false)
        private set
    var slot by mutableIntStateOf(-1)
        private set
    var host by mutableStateOf<String?>(null)
        private set
    var overTcp by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket: DatagramSocket? = null
    private var udpTarget: InetSocketAddress? = null
    private var tcpSocket: Socket? = null
    private var tcpOut: BufferedWriter? = null
    private var tcpIn: BufferedReader? = null
    private var sender: Job? = null
    private var receiver: Job? = null
    private var heartbeat: Job? = null
    private var lastPong = 0L
    private var lastTouch = 0L

    /** Vibración pedida por el juego: (milisegundos, amplitud 1..255). */
    var onRumble: ((Int, Int) -> Unit)? = null

    suspend fun connect(
        host: String,
        port: Int,
        name: String,
        useTcp: Boolean = false,
        pin: String = "",
    ): String? =
        withContext(Dispatchers.IO) {
            disconnect()
            if (useTcp) connectTcp(host, port, name, pin) else connectUdp(host, port, name, pin)
        }

    private fun helloMsg(name: String, pin: String): String {
        val json = JSONObject().put("t", "hello").put("name", name)
        if (pin.isNotEmpty()) json.put("pin", pin)
        return json.toString()
    }

    private fun connectUdp(host: String, port: Int, name: String, pin: String): String? {
        val sock = DatagramSocket()
        sock.broadcast = true
        return try {
            sock.soTimeout = 2500
            val address = InetSocketAddress(InetAddress.getByName(host), port)
            val hello = helloMsg(name, pin).toByteArray()
            sock.send(DatagramPacket(hello, hello.size, address))

            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            sock.receive(packet)
            val reply = JSONObject(String(packet.data, 0, packet.length))

            when (reply.optString("t")) {
                "welcome" -> {
                    sock.soTimeout = 700
                    udpSocket = sock
                    udpTarget = address
                    overTcp = false
                    return welcomed(host, port, reply)
                }

                "full" -> {
                    sock.close()
                    "La PC ya tiene los 8 mandos ocupados"
                }

                "error" -> {
                    sock.close()
                    pinError(reply)
                }

                else -> {
                    sock.close()
                    "Respuesta inesperada: ${reply.optString("t")}"
                }
            }
        } catch (e: SocketTimeoutException) {
            sock.close()
            "La PC no respondió (¿servidor activo y misma red?)"
        } catch (e: Exception) {
            sock.close()
            e.message ?: "Error de red"
        }
    }

    private fun connectTcp(host: String, port: Int, name: String, pin: String): String? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2500)
            socket.soTimeout = 2500
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.write(helloMsg(name, pin) + "\n")
            writer.flush()

            val line = reader.readLine() ?: return "La PC cerró la conexión"
            val reply = JSONObject(line)
            when (reply.optString("t")) {
                "welcome" -> {
                    socket.soTimeout = 0
                    tcpSocket = socket
                    tcpOut = writer
                    tcpIn = reader
                    overTcp = true
                    welcomed(host, port, reply)
                }

                "full" -> {
                    socket.close()
                    "La PC ya tiene los 8 mandos ocupados"
                }

                "error" -> {
                    socket.close()
                    pinError(reply)
                }

                else -> {
                    socket.close()
                    "Respuesta inesperada: ${reply.optString("t")}"
                }
            }
        } catch (e: SocketTimeoutException) {
            runCatching { socket?.close() }
            "La PC no respondió (revise el cable y `adb reverse`)"
        } catch (e: Exception) {
            runCatching { socket?.close() }
            e.message ?: "Error de red"
        }
    }

    private fun pinError(reply: JSONObject): String =
        if (reply.optString("msg") == "pin") "PIN incorrecto" else "Error del servidor"

    private fun welcomed(host: String, port: Int, reply: JSONObject): String? {
        slot = reply.optInt("slot", -1)
        this.host = host
        connected = true
        status = if (overTcp) "Conectado por USB ($port)" else "Conectado a $host:$port"
        lastPong = SystemClock.elapsedRealtime()
        listen()
        return null
    }

    /** Escucha respuestas (pong, rumble) y vigila que el servidor siga vivo. */
    private fun listen() {
        receiver?.cancel()
        heartbeat?.cancel()
        val sock = udpSocket
        val reader = tcpIn

        receiver = scope.launch {
            if (sock != null) {
                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    dispatch(String(packet.data, 0, packet.length))
                }
            } else if (reader != null) {
                while (isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (e: Exception) {
                        null
                    } ?: break
                    dispatch(line)
                }
            }
            if (connected) {
                status = "Conexión perdida"
                connected = false
            }
        }

        heartbeat = scope.launch {
            while (isActive) {
                delay(1500)
                val silence = SystemClock.elapsedRealtime() - lastPong
                if (silence > 6000) {
                    status = "Conexión perdida"
                    connected = false
                    break
                }
                sendRaw(JSONObject().put("t", "ping").put("ts", SystemClock.elapsedRealtime()).toString())
            }
        }
    }

    private fun dispatch(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "pong" -> lastPong = SystemClock.elapsedRealtime()
            "rumble" -> onRumble?.invoke(
                msg.optInt("ms", 120),
                msg.optInt("amp", 180),
            )
        }
    }

    /** Envía un JSON por el transporte activo; seguro entre corrutinas. */
    @Synchronized
    private fun sendRaw(text: String) {
        try {
            val sock = udpSocket
            val address = udpTarget
            val writer = tcpOut
            if (sock != null && address != null) {
                val payload = text.toByteArray()
                sock.send(DatagramPacket(payload, payload.size, address))
            } else if (writer != null) {
                writer.write(text + "\n")
                writer.flush()
            }
        } catch (e: Exception) {
            status = "Conexión perdida"
            connected = false
        }
    }

    /** Busca servidores por broadcast; devuelve la IP del primero que conteste. */
    suspend fun discover(port: Int, pin: String = ""): String? = withContext(Dispatchers.IO) {
        val sock = DatagramSocket()
        try {
            sock.broadcast = true
            sock.soTimeout = 400
            val hello = helloMsg("discover", pin).toByteArray()
            val broadcast = InetSocketAddress(InetAddress.getByName("255.255.255.255"), port)
            repeat(3) {
                sock.send(DatagramPacket(hello, hello.size, broadcast))
                Thread.sleep(80)
            }

            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    sock.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val reply = JSONObject(String(packet.data, 0, packet.length))
                if (reply.optString("t") == "welcome") {
                    val bye = JSONObject().put("t", "bye").toString().toByteArray()
                    runCatching {
                        sock.send(DatagramPacket(bye, bye.size, InetSocketAddress(packet.address, port)))
                    }
                    return@withContext packet.address.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Toque del touchpad: puntos normalizados 0..1 (máx. 2 dedos). */
    fun sendTouch(points: List<Pair<Float, Float>>) {
        if (!connected) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTouch < 16) return  // ~60 Hz máximo
        lastTouch = now
        val array = JSONArray()
        points.take(2).forEach { (x, y) ->
            array.put(
                JSONArray()
                    .put((x * 1000f).roundToInt() / 1000.0)
                    .put((y * 1000f).roundToInt() / 1000.0),
            )
        }
        val text = JSONObject().put("t", "touch").put("p", array).toString()
        scope.launch { sendRaw(text) }  // el socket no puede usarse en el hilo de UI
    }

    fun startStreaming(input: PadInput) {
        sender?.cancel()
        val sock = udpSocket
        val address = udpTarget
        val writer = tcpOut
        if (sock == null && writer == null) return

        sender = scope.launch {
            var lastMask = Int.MIN_VALUE
            var lastAxes = FloatArray(6) { Float.NaN }
            var lastSent = 0L
            while (isActive) {
                val mask = input.mask
                val axes = input.axes()
                val now = SystemClock.elapsedRealtime()
                val changed = mask != lastMask || !axes.contentEquals(lastAxes)
                if (changed || now - lastSent > 400) {
                    val message = JSONObject()
                        .put("t", "state")
                        .put(
                            "a",
                            JSONArray().apply {
                                axes.forEach { put((it * 1000f).roundToInt() / 1000.0) }
                            },
                        )
                        .put("b", mask)
                    sendRaw(message.toString())
                    if (!connected) break
                    lastMask = mask
                    lastAxes = axes
                    lastSent = now
                }
                delay(16)
            }
        }
    }

    fun disconnect() {
        sender?.cancel()
        sender = null
        receiver?.cancel()
        receiver = null
        heartbeat?.cancel()
        heartbeat = null

        udpSocket?.let { sock ->
            udpTarget?.let { address ->
                runCatching {
                    val bye = JSONObject().put("t", "bye").toString().toByteArray()
                    sock.send(DatagramPacket(bye, bye.size, address))
                }
            }
            runCatching { sock.close() }
        }
        tcpOut?.let { writer ->
            runCatching {
                writer.write(JSONObject().put("t", "bye").toString() + "\n")
                writer.flush()
            }
        }
        runCatching { tcpOut?.close() }
        runCatching { tcpIn?.close() }
        runCatching { tcpSocket?.close() }

        udpSocket = null
        udpTarget = null
        tcpSocket = null
        tcpOut = null
        tcpIn = null
        connected = false
        overTcp = false
        slot = -1
        status = "Sin conexión"
    }
}

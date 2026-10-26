# HANDOFF — Phonepad

Estado al 2026-10-26. Sirve para retomar el proyecto con cualquier modelo o sesión.

## Punto de partida (2026-10-26)

- Repo git local en `~/Proyectos/phonepad`, commit **c0dad201** (sin remoto).
  Volver aquí: `git -C ~/Proyectos/phonepad checkout c0dad201`.
- Funciona: servidor UDP/TCP/WebSocket, DS4 virtual por uinput, cliente web,
  APK Android con editor de layout. Verificado con `tools/selftest.py` (OK) y
  con el teléfono real (slot 1 creado y liberado).
- Servicio: `systemctl --user status phonepad` (arranca al iniciar sesión).
- Sin probar aún: un juego real, giroscopio, rumble, Windows.
- Comprobación rápida tras cualquier cambio: `python3 tools/selftest.py`.


## Objetivo

Mando virtual tipo DualShock 4 en la PC, controlado desde el celular por WiFi.
Hasta 8 teléfonos = 8 mandos simultáneos. Linux primero, Windows después.

Decisión tomada: **WiFi UDP**, no Bluetooth (Android no permite emular HID
gamepad sin root; latencia 20-60 ms vs 4-13 ms).

## Lo que ya funciona (probado)

```bash
cd ~/Proyectos/phonepad && ./run.sh          # servidor
python3 tools/selftest.py                    # prueba de humo: OK
```

`tools/selftest.py` verifica de punta a punta: handshake UDP, handshake
WebSocket, creación de dos `/dev/input/eventN` y recepción real de eventos
(señal `BTN_SOUTH`, `ABS_X`, `ABS_RZ`). Última salida: `OK: UDP, WebSocket y
mandos virtuales funcionan`.

Archivos:

```
phonepad/
├── phonepad/protocol.py      # botones, ejes, timeouts, mensajes
├── phonepad/pad_linux.py     # DS4 virtual con /dev/uinput
├── phonepad/server.py        # UDP + WebSocket + HTTP estático
├── phonepad/web/index.html   # mando táctil (navegador, ya usable)
├── tools/selftest.py         # prueba de integración
├── tools/install_android_sdk.sh
├── run.sh
└── README.md                 # uso y protocolo
```

Protocolo: JSON. `{"t":"hello","name":...}` → `{"t":"welcome","slot":0,"max":8}`;
estado a ~60 Hz `{"t":"state","a":[lx,ly,rx,ry,l2,r2],"b":máscara}`. La máscara
sigue el orden de `phonepad/protocol.py::BUTTONS`. Slot libre tras 6 s sin
tráfico. Puertos: 8080 HTTP, 8099 WS, 9001 UDP.

## Entorno ya instalado (sin root)

| Cosa | Ruta |
|---|---|
| JDK 21 Temurin | `~/Android/jdk21` |
| Android SDK | `~/Android/Sdk` |
| build-tools | 35.0.0 |
| platform | android-35 |
| platform-tools (adb) | 37.0.1 |
| Descargas | `~/Android/dl` |

`/dev/uinput` escribible por el usuario (grupo `input` + ACL). Python 3.14 con
`python-evdev`. Sin firewall activo (`ufw`/`firewalld` inactivos, `nft` vacío).

Variables para compilar:

```bash
export JAVA_HOME=~/Android/jdk21
export ANDROID_HOME=~/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
```

Nota: `sdkmanager` avisa que está deprecado; sigue funcionando. El binario
nuevo es `~/Android/Sdk/cmdline-tools/latest/bin/android`.

## Hecho — app Android nativa

Compila y funciona: `./android/build.sh` → `phonepad/web/phonepad.apk`
(8,4 MB, `dev.roque.phonepad`, minSdk 26, targetSdk 35). Verificado con
`aapt2 dump badging`: activity `dev.roque.phonepad.MainActivity`, label
"Phonepad". Sin probar todavía en un teléfono real.

Código: `android/app/src/main/java/dev/roque/phonepad/`
- `PadLink.kt` — UDP, descubrimiento por broadcast, envío de estado a 60 Hz.
- `MainActivity.kt` — pantalla de conexión + mando Compose con multitáctil.

Toolchain usada: Gradle 8.9 en `~/Android/gradle/gradle-8.9`, AGP 8.7.3,
Kotlin 2.0.21, Compose BOM 2024.10.00. AGP instaló solo build-tools 34.
Aviso inocuo: "SDK XML version 4" (cmdline-tools nuevos, AGP viejo).

Pendiente de esta fase: instalar el APK en el celular y validar latencia,
giroscopio y vibración real (no implementados aún).

### Editor de layout (hecho)

Cada control es un `PadItem` (`PadLayout.kt`) con posición normalizada `fx/fy`
y `scale`. `PadSlot` lo coloca con `offset{ maxX*fx , maxY*fy }` y en modo
edición aplica `detectTransformGestures` (pan + pinch, zoom 0.6-2.2).
Persistencia en SharedPreferences, clave `layout_v1`, JSON
`{id: {x, y, s}}`. Botón **editar** en el HUD; **restablecer** borra la clave.

### USB sin WiFi (evita el router)

El servidor escucha el mismo puerto 9001 en TCP (líneas JSON) además de UDP.
Con el teléfono por cable y depuración USB activa:

```bash
adb install -r phonepad/web/phonepad.apk
adb reverse tcp:8080 tcp:8080     # ver la página en el teléfono
adb reverse tcp:9001 tcp:9001     # transporte del mando
```

En la app: activar **Por USB (adb reverse)** y Conectar (usa 127.0.0.1:9001).
En el navegador del teléfono: `http://localhost:8080/`.
Validado por `tools/selftest.py` (bloque TCP: triángulo llega al kernel) y
desde el propio teléfono: `curl` al 8080 devuelve 200 y un `hello` por nc al
9001 creó y liberó el mando en el servidor.

Ojo: el teléfono es Xiaomi y `adb install` falla con
`INSTALL_FAILED_USER_RESTRICTED`. Opciones: activar *Ajustes → Opciones de
desarrollador → Instalar vía USB*, o instalar a mano el APK ya copiado en
`/sdcard/Download/phonepad.apk`.

La IP del portátil es DHCP (hoy `192.168.1.25`); no asumir una fija.

### Plan original (referencia)

- Kotlin + Jetpack Compose, `compileSdk 35`, `minSdk 26`, landscape fijo,
  inmersivo (ocultar barras), pantalla despierta.
- Mismo protocolo JSON por **UDP** (puerto 9001), ~60-125 Hz, solo enviar si
  cambia el estado + latido cada 400 ms.
- Red: campo de IP manual + descubrimiento por broadcast UDP en el puerto 9001
  (`hello` a 255.255.255.255) y guardar la última IP.
- Layout idéntico al web: L1/L2 esquina superior izquierda, R1/R2 superior
  derecha, stick izquierdo con L3 al centro, stick derecho con R3 al centro,
  dpad al centro izquierda, △○×□ al centro derecha, SHARE/PS/START abajo.
- Multitáctil por `pointerId` (no usar `onTouchEvent` por vista), sin
  `pointerInterop`, con `detectTapGestures` fuera del camino crítico.

Sin Gradle instalado en el sistema. Dos rutas:

1. **Recomendada**: descargar `gradle-8.9-bin.zip` a `~/Android/gradle`,
   generar wrapper con `gradle-8.9/bin/gradle wrapper` dentro de `android/`
   y compilar con `./gradlew assembleDebug`.
2. Alternativa sin Gradle: `aapt2` + `kotlinc` + `d8` + `apksigner` a mano
   (más frágil; solo si la ruta 1 falla).

Versiones sugeridas: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.00.
Primera compilación: 3-6 min; descargas ~400 MB.

Instalación en el celular sin cable: copiar el APK a
`phonepad/phonepad/web/` y descargarlo desde `http://IP:8080/` en el navegador.

## Pendiente 2 — red del celular (bloqueante para probar)

El celular no cargó `http://192.168.1.20:8080/`. El servidor sí responde en
local (`curl` → 200) y escucha en `0.0.0.0`. Sospecha: aislamiento de clientes
del router (AP isolation) o SSID/banda distinta.

Diagnóstico pendiente: conectar la laptop al hotspot del celular y repetir; o
probar `ping 192.168.1.20` desde el teléfono. Si el hotspot funciona, el
problema es el router.

Servidor de prueba en segundo plano:

```bash
cd ~/Proyectos/phonepad && setsid nohup ./run.sh >/tmp/phonepad.log 2>&1 &
# parar: pkill -f phonepad.server
```

## Pendiente 3 — Windows

Backend `vgamepad` (ViGEmBus), máximo 4 mandos. Interfaz común a implementar:
`create(slot)`, `update(axes, mask)`, `close()`. Reutilizar `server.py` y la
app Android tal cual.

## Cosas que NO repetir

- Bluetooth: descartado.
- PWA/navegador como producto final: sirve solo para pruebas (ya está).
- `evdev.UInput` no acepta `uniq=` (falla con TypeError); sí acepta `phys=`.
- La prueba de humo debe localizar el mando por `dev.phys == "phonepad/<slot>"`,
  no por nombre, porque el nombre es `Phonepad Slot <slot+1>`.

# HANDOFF — PocketPad

Estado al 2026-10-26. Sirve para retomar el proyecto con cualquier modelo o sesión.

## Punto de partida (2026-10-30)

- Público: <https://github.com/Matrixpro777/pocketpad> (MIT), release **v0.4**
  con `app-release.apk` firmado (clave en `~/.android-keys/pocketpad.jks` y
  `android/keystore.properties`, fuera de git).
- Código en `~/Proyectos/pocketpad`; paquete Android `dev.roque.pocketpad`.
- Servicios de usuario: `pocketpad.service` (servidor) y `pocketpad-usb.service`
  (rehace `adb reverse` 9001/8080 cada 5 s si el adb se reinicia).
- Hecho: DS4 por uinput con nombre/PID/versión por slot, UDP+TCP+WebSocket,
  editor de layout con perfiles por juego, giroscopio, rumble real (EV_FF de
  uinput → `{"t":"rumble"}` → vibración), reconexión automática, L3/R3 solo con
  toque seco.
- Comprobación rápida: `python3 tools/selftest.py` (OK el 2026-10-30).
- Probado en juegos: CTR (SacredFire) 2 jugadores; GOW3 (port) detecta el mando;
  BT3 (Tenkaichi3Decomp) ya trae el mapeo estándar en `bt3_settings.txt`.
- Pendiente: Windows/macOS, touchpad del DS4, curva/zona muerta de sticks, PIN
  de red, aviso in-app de plataformas futuras.

## Objetivo

Mando virtual tipo DualShock 4 en la PC, controlado desde el celular por WiFi.
Hasta 8 teléfonos = 8 mandos simultáneos. Linux primero, Windows después.

Decisión tomada: **WiFi UDP**, no Bluetooth (Android no permite emular HID
gamepad sin root; latencia 20-60 ms vs 4-13 ms).

## Lo que ya funciona (probado)

```bash
cd ~/Proyectos/pocketpad && ./run.sh          # servidor
python3 tools/selftest.py                    # prueba de humo: OK
```

`tools/selftest.py` verifica de punta a punta: handshake UDP, handshake
WebSocket, creación de dos `/dev/input/eventN` y recepción real de eventos
(señal `BTN_SOUTH`, `ABS_X`, `ABS_RZ`). Última salida: `OK: UDP, WebSocket y
mandos virtuales funcionan`.

Archivos:

```
pocketpad/
├── pocketpad/protocol.py      # botones, ejes, timeouts, mensajes
├── pocketpad/pad_linux.py     # DS4 virtual con /dev/uinput
├── pocketpad/server.py        # UDP + WebSocket + HTTP estático
├── pocketpad/web/index.html   # mando táctil (navegador, ya usable)
├── tools/selftest.py         # prueba de integración
├── tools/install_android_sdk.sh
├── run.sh
└── README.md                 # uso y protocolo
```

Protocolo: JSON. `{"t":"hello","name":...}` → `{"t":"welcome","slot":0,"max":8}`;
estado a ~60 Hz `{"t":"state","a":[lx,ly,rx,ry,l2,r2],"b":máscara}`. La máscara
sigue el orden de `pocketpad/protocol.py::BUTTONS`. Slot libre tras 6 s sin
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

Compila y funciona: `./android/build.sh` → `pocketpad/web/pocketpad.apk`
(8,4 MB, `dev.roque.pocketpad`, minSdk 26, targetSdk 35). Verificado con
`aapt2 dump badging`: activity `dev.roque.pocketpad.MainActivity`, label
"PocketPad". Sin probar todavía en un teléfono real.

Código: `android/app/src/main/java/dev/roque/pocketpad/`
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
adb install -r pocketpad/web/pocketpad.apk
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
`/sdcard/Download/pocketpad.apk`.

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
`pocketpad/pocketpad/web/` y descargarlo desde `http://IP:8080/` en el navegador.

## Pendiente 2 — red del celular (bloqueante para probar)

El celular no cargó `http://192.168.1.20:8080/`. El servidor sí responde en
local (`curl` → 200) y escucha en `0.0.0.0`. Sospecha: aislamiento de clientes
del router (AP isolation) o SSID/banda distinta.

Diagnóstico pendiente: conectar la laptop al hotspot del celular y repetir; o
probar `ping 192.168.1.20` desde el teléfono. Si el hotspot funciona, el
problema es el router.

Servidor de prueba en segundo plano:

```bash
cd ~/Proyectos/pocketpad && setsid nohup ./run.sh >/tmp/pocketpad.log 2>&1 &
# parar: pkill -f pocketpad.server
```

## Pendiente 3 — Windows

Backend `vgamepad` (ViGEmBus), máximo 4 mandos. Interfaz común a implementar:
`create(slot)`, `update(axes, mask)`, `close()`. Reutilizar `server.py` y la
app Android tal cual.

## Cosas que NO repetir

- Bluetooth: descartado.
- PWA/navegador como producto final: sirve solo para pruebas (ya está).
- `evdev.UInput` no acepta `uniq=` (falla con TypeError); sí acepta `phys=`.
- La prueba de humo debe localizar el mando por `dev.phys == "pocketpad/<slot>"`,
  no por nombre, porque el nombre es `PS4Controller<slot+1>`.

### Marca y créditos (v0.2)

Icono adaptativo (`res/mipmap-anydpi-v26/ic_launcher.xml`, cuatro botones de
PlayStation sobre `#0B0D12`), autor «por Roque» en la pantalla de conexión y
diálogo de créditos (`CreditsDialog` en `MainActivity.kt`). `versionName 0.2`.

Mejoras propuestas, sin empezar: perfiles de layout por juego (varias claves
`layout_*` en vez de una), reconexión automática al último PC, rumble real
(leer force feedback del uinput y enviarlo por UDP) y giroscopio.

### Stick L3/R3 y perfiles (v0.3)

`StickPad` ya no decide «clic» al apoyar el dedo: mueve el stick desde el primer
píxel y solo pulsa L3/R3 si el dedo se levanta sin desplazarse más de 12 dp y
antes de 400 ms. Así se acabó el R3 fantasma al conducir. `PadInput.stickRightHeld`
avisa al giroscopio de que hay dedo en el stick derecho.

`PadLayout` guarda perfiles por juego (`layout_v2`, JSON `profiles` + `current`,
migración desde `layout_v1`). El HUD muestra el perfil y abre el diálogo para
crear, cambiar o borrar. El HUD también enciende el giroscopio («giro»).

### Rumble real y reconexión (v0.4)

El kernel sí reenvía la fuerza por el descriptor de uinput: `EV_UINPUT`
(UI_FF_UPLOAD / UI_FF_ERASE) y `EV_FF` con el id del efecto al empezar y al
parar. `VirtualPad` declara `EV_FF: [FF_RUMBLE]` con `max_effects=16` y un hilo
`_ff_loop` que contesta esas peticiones; al reproducirse un efecto avisa con
`on_rumble(amplitud 0..255, ms)`. `Hub._push_rumble` lo manda a la sesión del
slot como `{"t":"rumble","amp":…,"ms":…}`. La app vibra con
`VibrationEffect.createOneShot`.

Aviso: probar el FF desde el mismo proceso Python que tiene el pad se cuelga
(`upload_effect` retiene el GIL mientras espera la respuesta); usar siempre un
proceso cliente aparte, como hace el juego.

Reconexión: `PadLink` escucha pong/rumble, manda `ping` cada 1,5 s y da la
sesión por perdida a los 6 s sin respuesta. `PocketPadApp` reintenta 40 veces
cada 1,2 s con el último host/puerto guardados (el modo USB también se guarda).

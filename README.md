# PocketPad

Mando DualShock 4 virtual en la PC, controlado desde hasta 8 teléfonos por WiFi.
Funciona en Linux (uinput). Windows quedará para una segunda fase (ViGEmBus).

## Descargar e instalar (para cualquiera)

[![release](https://img.shields.io/github/v/release/Matrixpro777/pocketpad)](https://github.com/Matrixpro777/pocketpad/releases/latest)

1. Descargue el APK de la [última versión](https://github.com/Matrixpro777/pocketpad/releases/latest).
2. Instálelo en el teléfono (permita «instalar desde esta fuente»).
3. En la PC: `git clone https://github.com/Matrixpro777/pocketpad && cd pocketpad && ./run.sh`
   (o el servicio: `systemctl --user enable --now pocketpad`).
4. Abra la app, pulse **Buscar en la red** o escriba la IP de la PC y **Conectar**.

## Instalación paso a paso

### En la PC (Linux)

```bash
git clone https://github.com/Matrixpro777/pocketpad
cd pocketpad
python3 -m venv .venv && . .venv/bin/activate   # opcional
pip install evdev
./run.sh
```

Para que arranque solo con la sesión:

```bash
cp pocketpad.service.example ~/.config/systemd/user/pocketpad.service
systemctl --user enable --now pocketpad.service
```

Deje abiertos los puertos **9001/udp+tcp**, **8099/tcp** y **8080/tcp** (firewall
del sistema; en Arch con `ufw`/`firewalld` suele estar cerrado por defecto).

### En el teléfono

1. Descargue el APK de la [última versión](https://github.com/Matrixpro777/pocketpad/releases/latest).
2. Ábralo y permita «instalar apps de esta fuente» cuando el sistema lo pida.
3. Abra **PocketPad** y pulse **Buscar en la red** (o escriba la IP que muestra
   la PC al arrancar) y luego **Conectar**.
4. El HUD confirma `mando N`; cada teléfono recibe uno distinto, hasta 8.

### Por USB (sin WiFi)

Con el teléfono conectado y depuración USB activa:

```bash
adb reverse tcp:9001 tcp:9001
adb reverse tcp:8080 tcp:8080
```

En la app active **Por USB** y conéctese a `127.0.0.1:9001`.

Para que el mapeo sobreviva a reinicios del servidor adb, hay un vigilante
incluido (`pocketpad-usb.service`): vuelve a crear `adb reverse` cada 5 s si
falta. Instalación:

```bash
install -m755 pocketpad-usb ~/.local/bin/
install -m644 pocketpad-usb.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now pocketpad-usb
```

El teléfono y la PC deben estar en **la misma red**: dos SSID distintos (aunque
se llamen parecido) no se ven entre sí.

### Si algo no funciona

- **No encuentra la PC**: misma red, firewall abierto, y pruebe la IP a mano.
- **El mando no aparece en el juego**: reinicie el juego con la app ya conectada.
- **`/dev/uinput` sin permisos**: `sudo usermod -aG input $USER` y reinicie sesión.
- **Se cae la conexión**: la app reconecta sola; compruebe `systemctl --user status pocketpad`.

## Requisitos

- Python 3.10+ y `python-evdev`
- Acceso a `/dev/uinput` (grupo `input` o ACL; en Arch ya está)
- Teléfono y PC en la misma red WiFi

## Arrancar

```bash
cd ~/Proyectos/pocketpad
./run.sh
```

Salida típica:

```
nav web : http://192.168.1.20:8080/
udp     : 9001   (app nativa)
ws      : 8099   (navegador)
```

Abre esa URL en el celular, pulsa ⛶ para pantalla completa y verás el mando.
El HUD indica qué mando virtual te tocó (`mando 1 de 8`).

## App Android

```bash
./android/build.sh            # compila y copia el APK a pocketpad/web/
```

Descarga e instala desde el celular: `http://IP:8080/pocketpad.apk`
(permite "instalar apps desconocidas" para el navegador). Abre la app, pulsa
**Buscar en la red** o escribe la IP de la PC y **Conectar**. Recuerda la IP.
El APK es de depuración (firmado con clave debug), no requiere cuenta Google.

Con el mando en pantalla, **editar** entra en modo colocación: arrastrar mueve,
pellizcar escala, **listo** guarda el layout en el teléfono y **restablecer**
vuelve a los valores por defecto.

## Mando virtual

Cada conexión crea un `/dev/input/eventN` llamado `PS4ControllerN`
(VID 054C, PID 09CC: DualShock 4 v2). Al desconectarse se destruye solo.

Verificar a mano:

```bash
ls /dev/input/by-path/ | grep -i event   # o
python3 -c "import evdev; print([(d.path, d.name) for d in map(evdev.InputDevice, evdev.list_devices())])"
sudo evtest      # elige "PS4Controller1"
```

## Botones

| Zona | Controles |
|---|---|
| Esquina superior izquierda | L1 y L2 |
| Esquina superior derecha | R1 y R2 |
| Abajo izquierda | stick izquierdo (centro = L3) |
| Abajo derecha | stick derecho (centro = R3) |
| Centro izquierda | flechas (dpad) |
| Centro derecha | △ ○ × □ |
| Centro abajo | SHARE, PS, START |
| Gatillos | L2/R2 son eje analógico (0-255) y botón a la vez |

## Puertos

| Puerto | Uso |
|---|---|
| 8080/tcp | página del mando |
| 8099/tcp | WebSocket (navegador) |
| 9001/udp | cliente nativo (app Android, menor latencia) |

Cambiar: `./run.sh --http-port 8080 --ws-port 8099 --udp-port 9001`

## Protocolo

JSON. Registro: `{"t":"hello","name":"...","pin":"1234"}` →
`{"t":"welcome","slot":0,"max":8}`. Con `--pin` (o `POCKETPAD_PIN=1234`) el
servidor exige el PIN y responde `{"t":"error","msg":"pin"}` si falta o no
coincide.
Estado a ~60 Hz: `{"t":"state","a":[lx,ly,rx,ry,l2,r2],"b":máscara}`,
ejes en -1..1 (gatillos 0..1) y máscara de bits en el orden de
`pocketpad/protocol.py`. Touchpad: `{"t":"touch","p":[[x,y],[x,y]]}` con
coordenadas normalizadas 0..1 (hasta dos dedos). Sin tráfico durante 6 s el
slot se libera.

## Pruebas

```bash
python3 tools/selftest.py
```

Levanta el servidor real y valida UDP, WebSocket y la emisión al kernel.

## Próximamente

- **Windows**: backend `pocketpad/pad_windows.py` (ViGEmBus + `vgamepad`) ya
  escrito con la misma interfaz; sin probar en una máquina Windows.
- **macOS**: en investigación (no hay equivalente libre a uinput/ViGEm).
- **iOS**: depende de poder enviar UDP en segundo plano; sin fecha.

Solo Linux está probado: en Windows el servidor debería arrancar con `vgamepad`
instalado (4 mandos), pero falta validarlo. En macOS el servidor **no** funciona.

## Publicar una versión

```bash
./android/build.sh assembleRelease        # APK firmado (clave en ~/.android-keys)
gh release create v0.4 android/app/build/outputs/apk/release/app-release.apk \
   --title "PocketPad 0.4" --notes "rumble real y reconexión automática"
```

MIT © Roque. Las claves de firma viven en `~/.android-keys/pocketpad.jks` y
`android/keystore.properties` (ambas fuera de git): sin ellas no se pueden
publicar actualizaciones que actualicen la app instalada.

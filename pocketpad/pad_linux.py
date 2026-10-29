"""Mando DualShock 4 virtual en Linux mediante /dev/uinput (python-evdev)."""

import select
import threading
import time

from evdev import AbsInfo, InputEvent, UInput
from evdev import ecodes as e

from .protocol import AXES, BUTTONS

AXIS_INFO = {
    "lx": (e.ABS_X, -32768, 32767),
    "ly": (e.ABS_Y, -32768, 32767),
    "rx": (e.ABS_RX, -32768, 32767),
    "ry": (e.ABS_RY, -32768, 32767),
    "l2": (e.ABS_Z, 0, 255),
    "r2": (e.ABS_RZ, 0, 255),
}

BUTTON_CODES = {
    "cross": e.BTN_SOUTH,
    "circle": e.BTN_EAST,
    "square": e.BTN_WEST,
    "triangle": e.BTN_NORTH,
    "l1": e.BTN_TL,
    "r1": e.BTN_TR,
    "l2": e.BTN_TL2,
    "r2": e.BTN_TR2,
    "l3": e.BTN_THUMBL,
    "r3": e.BTN_THUMBR,
    "share": e.BTN_SELECT,
    "options": e.BTN_START,
    "ps": e.BTN_MODE,
}

# Las flechas van como HAT (eje digital) para que SDL/Wine las entiendan.
DPAD_CODES = {
    "up": (e.ABS_HAT0Y, -1),
    "down": (e.ABS_HAT0Y, 1),
    "left": (e.ABS_HAT0X, -1),
    "right": (e.ABS_HAT0X, 1),
}

BIT_INDEX = {name: i for i, name in enumerate(BUTTONS)}


def _axis_value(name, raw):
    raw = -1.0 if raw < -1.0 else 1.0 if raw > 1.0 else float(raw)
    if name in ("l2", "r2"):
        return int(round(max(0.0, raw) * 255))
    return int(round(raw * 32767)) if raw >= 0 else int(round(raw * 32768))


class VirtualPad:
    """Un DS4 virtual independiente. update() solo emite diferencias.

    Vibración: el kernel reenvía por el descriptor de uinput las peticiones de
    fuerza (EV_UINPUT) y la reproducción de efectos (EV_FF), así que se puede
    avisar al teléfono con on_rumble(amplitud 0..255, milisegundos).
    """

    def __init__(self, slot, on_rumble=None):
        self.slot = slot
        self.on_rumble = on_rumble
        self._axes = {}
        self._keys = {}
        self._hats = {e.ABS_HAT0X: 0, e.ABS_HAT0Y: 0}
        self._effects = {}
        self._last_rumble = 0.0
        self._closed = False

        events = {
            e.EV_KEY: sorted(BUTTON_CODES.values()),
            e.EV_ABS: [
                (code, AbsInfo(0, lo, hi, 0, 0, 0))
                for code, lo, hi in AXIS_INFO.values()
            ]
            + [
                (e.ABS_HAT0X, AbsInfo(0, -1, 1, 0, 0, 0)),
                (e.ABS_HAT0Y, AbsInfo(0, -1, 1, 0, 0, 0)),
            ],
            e.EV_FF: [e.FF_RUMBLE],
        }
        self.ui = UInput(
            events,
            name=f"PS4Controller{slot + 1}",
            vendor=0x054C,
            # producto distinto por slot: SDL asocia 054c:09cc a "PS4 Controller"
            # sin número; al cambiar el PID usa el nombre del dispositivo.
            product=0x09CC + slot,
            version=0x8111 + slot,
            bustype=e.BUS_USB,
            phys=f"pocketpad/{slot}",
            max_effects=16,
        )
        threading.Thread(target=self._ff_loop, daemon=True).start()

    # -- emisión -----------------------------------------------------
    def _emit(self, etype, code, value):
        self.ui.write_event(InputEvent(time.time(), 0, etype, code, value))

    def update(self, axes, mask):
        changed = False

        for i, name in enumerate(AXES):
            code = AXIS_INFO[name][0]
            value = _axis_value(name, axes[i] if i < len(axes) else 0.0)
            if self._axes.get(code) != value:
                self._emit(e.EV_ABS, code, value)
                self._axes[code] = value
                changed = True

        for name, code in BUTTON_CODES.items():
            value = 1 if mask & (1 << BIT_INDEX[name]) else 0
            if self._keys.get(code) != value:
                self._emit(e.EV_KEY, code, value)
                self._keys[code] = value
                changed = True

        hx = hy = 0
        for name, (code, direction) in DPAD_CODES.items():
            if mask & (1 << BIT_INDEX[name]):
                if code == e.ABS_HAT0X:
                    hx = direction
                else:
                    hy = direction
        for code, value in ((e.ABS_HAT0X, hx), (e.ABS_HAT0Y, hy)):
            if self._hats[code] != value:
                self._emit(e.EV_ABS, code, value)
                self._hats[code] = value
                changed = True

        if changed:
            self.ui.syn()

    # -- fuerza -------------------------------------------------------
    def _ff_loop(self):
        fd = self.ui.fd
        while not self._closed:
            try:
                ready, _, _ = select.select([fd], [], [], 0.3)
            except (OSError, ValueError):
                return
            if not ready:
                continue
            try:
                event = self.ui.read_one()
            except (OSError, ValueError):
                return
            if event is None:
                continue
            if event.type == e.EV_UINPUT:
                self._ff_request(event)
            elif event.type == e.EV_FF and event.code in self._effects:
                self._ff_playback(event.code, event.value)

    def _ff_request(self, event):
        try:
            if event.code == e.UI_FF_UPLOAD:
                upload = self.ui.begin_upload(event.value)
                effect = upload.effect
                if effect.type == e.FF_RUMBLE:
                    rumble = effect.u.ff_rumble_effect
                    self._effects[effect.id] = (
                        rumble.strong_magnitude,
                        rumble.weak_magnitude,
                        effect.ff_replay.length,
                    )
                upload.retval = 0
                self.ui.end_upload(upload)
            elif event.code == e.UI_FF_ERASE:
                erase = self.ui.begin_erase(event.value)
                self._effects.pop(erase.effect_id, None)
                erase.retval = 0
                self.ui.end_erase(erase)
        except Exception:
            pass

    def _ff_playback(self, effect_id, value):
        if not value or not self.on_rumble:
            return
        now = time.monotonic()
        if now - self._last_rumble < 0.03:
            return
        self._last_rumble = now
        strong, weak, length = self._effects[effect_id]
        magnitude = int(max(strong, weak) / 257)  # 0..65535 -> 0..255
        if magnitude <= 0:
            return
        milliseconds = length if 10 <= length <= 2000 else 120
        try:
            self.on_rumble(magnitude, milliseconds)
        except Exception:
            pass

    def reset(self):
        self.update([0.0] * len(AXES), 0)

    def close(self):
        self._closed = True
        try:
            self.reset()
            self.ui.close()
        except Exception:
            pass

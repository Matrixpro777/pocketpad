"""Mando Xbox 360 virtual en Windows mediante ViGEmBus (paquete `vgamepad`).

Misma interfaz que `pad_linux.VirtualPad` para que `server.py` no cambie:
`update(axes, mask)`, `touch(points)`, `reset()`, `close()` y `on_rumble`.

Windows no entrega el touchpad del DS4 por XInput, así que `touch()` no hace
nada: el resto de la app funciona igual (la zona del touchpad queda inerte).
"""

import threading

from .protocol import AXES, BUTTONS

BIT_INDEX = {name: i for i, name in enumerate(BUTTONS)}

# Botones de la máscara -> nombre del botón XUSB en vgamepad.
BUTTON_NAMES = {
    "cross": "XUSB_GAMEPAD_A",
    "circle": "XUSB_GAMEPAD_B",
    "square": "XUSB_GAMEPAD_X",
    "triangle": "XUSB_GAMEPAD_Y",
    "l1": "XUSB_GAMEPAD_LEFT_SHOULDER",
    "r1": "XUSB_GAMEPAD_RIGHT_SHOULDER",
    "l3": "XUSB_GAMEPAD_LEFT_THUMB",
    "r3": "XUSB_GAMEPAD_RIGHT_THUMB",
    "share": "XUSB_GAMEPAD_BACK",
    "options": "XUSB_GAMEPAD_START",
    "ps": "XUSB_GAMEPAD_GUIDE",
    "up": "XUSB_GAMEPAD_DPAD_UP",
    "down": "XUSB_GAMEPAD_DPAD_DOWN",
    "left": "XUSB_GAMEPAD_DPAD_LEFT",
    "right": "XUSB_GAMEPAD_DPAD_RIGHT",
}


def _clamp(raw, low=-1.0, high=1.0):
    return low if raw < low else high if raw > high else float(raw)


class WindowsPad:
    """Un mando XInput virtual por cada slot (ViGEmBus admite 4)."""

    def __init__(self, slot, on_rumble=None):
        import vgamepad as vg  # se importa aquí: en Linux este módulo no existe

        self._vg = vg
        self.slot = slot
        self.on_rumble = on_rumble
        self.pad = vg.VX360Gamepad()
        self._axes = (0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        self._mask = 0
        self._closed = False
        self._register_rumble()

    def _register_rumble(self):
        def notify(_client, _target, large_motor, small_motor, _led, _data):
            if not self.on_rumble:
                return
            magnitude = max(large_motor, small_motor, 0)
            if magnitude <= 0:
                return
            try:
                self.on_rumble(int(magnitude), 120)
            except Exception:
                pass

        try:
            self.pad.register_notification(notify)
        except Exception:
            pass  # vgamepad viejo o sin soporte de avisos

    # -- emisión -----------------------------------------------------
    def update(self, axes, mask):
        vg = self._vg
        values = [0.0] * 6
        for i in range(min(6, len(axes))):
            values[i] = _clamp(axes[i])
        values = tuple(values)
        mask = int(mask)

        if values != self._axes:
            self.pad.left_joystick(x_value=int(values[0] * 32767), y_value=int(-values[1] * 32767))
            self.pad.right_joystick(x_value=int(values[2] * 32767), y_value=int(-values[3] * 32767))
            self.pad.left_trigger(value=int(max(0.0, values[4]) * 255))
            self.pad.right_trigger(value=int(max(0.0, values[5]) * 255))
            self._axes = values

        if mask != self._mask:
            for name, attr in BUTTON_NAMES.items():
                code = getattr(vg, attr)
                if mask & (1 << BIT_INDEX[name]):
                    self.pad.press_button(button=code)
                else:
                    self.pad.release_button(button=code)
            self._mask = mask

        try:
            self.pad.update()
        except Exception:
            pass

    def touch(self, points):
        """XInput no tiene touchpad: se ignora."""

    def reset(self):
        self.update([0.0] * len(AXES), 0)

    def close(self):
        self._closed = True
        try:
            self.reset()
            self.pad.reset()
        except Exception:
            pass

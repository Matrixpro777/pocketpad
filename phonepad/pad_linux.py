"""Mando DualShock 4 virtual en Linux mediante /dev/uinput (python-evdev)."""

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
    """Un DS4 virtual independiente. update() solo emite diferencias."""

    def __init__(self, slot):
        self.slot = slot
        self._axes = {}
        self._keys = {}
        self._hats = {e.ABS_HAT0X: 0, e.ABS_HAT0Y: 0}

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
        }
        self.ui = UInput(
            events,
            name=f"PS4Controller{slot + 1}",
            vendor=0x054C,
            product=0x09CC,
            # versión distinta por slot: SDL deriva el GUID del dispositivo de
            # vendor/product/version, y sin esto los 8 mandos son indistinguibles.
            version=0x8111 + slot,
            bustype=e.BUS_USB,
            phys=f"phonepad/{slot}",
        )

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

    def reset(self):
        self.update([0.0] * len(AXES), 0)

    def close(self):
        try:
            self.reset()
            self.ui.close()
        except Exception:
            pass

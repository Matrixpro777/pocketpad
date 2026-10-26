"""Protocolo compartido entre servidor, cliente web y app Android."""

MAX_PADS = 8

# Orden fijo: el bit i corresponde a BUTTONS[i] en la máscara "b".
BUTTONS = [
    "cross", "circle", "square", "triangle",
    "l1", "r1", "l2", "r2",
    "l3", "r3", "share", "options", "ps",
    "up", "down", "left", "right",
]
BIT = {name: 1 << i for i, name in enumerate(BUTTONS)}

# Orden fijo de ejes en la lista "a".
AXES = ("lx", "ly", "rx", "ry", "l2", "r2")

DEADZONE = 0.10
IDLE_TIMEOUT = 6.0   # s sin estado -> se libera el mando


def state_msg(axes, mask, seq=0):
    return {"t": "state", "seq": seq, "a": list(axes), "b": int(mask)}


def welcome_msg(slot, name):
    return {"t": "welcome", "slot": slot, "name": name, "max": MAX_PADS}

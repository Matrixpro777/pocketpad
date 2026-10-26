#!/usr/bin/env python3
"""Prueba de humo: levanta el servidor real y valida UDP, WebSocket y uinput."""

import asyncio
import base64
import hashlib
import json
import os
import socket
import struct
import sys
from pathlib import Path

import evdev
from evdev import ecodes as e

ROOT = Path(__file__).resolve().parent.parent
UDP_PORT, WS_PORT, HTTP_PORT = 9002, 8098, 8081
UDP_ADDR = ("127.0.0.1", UDP_PORT)
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def find_pad(slot=0, timeout=3.0):
    import time
    end = time.time() + timeout
    while time.time() < end:
        for path in evdev.list_devices():
            try:
                dev = evdev.InputDevice(path)
            except OSError:
                continue
            if dev.phys == f"phonepad/{slot}":
                return dev
            dev.close()
        time.sleep(0.05)
    raise SystemExit("FALLO: no apareció el mando virtual")


def ws_frame(payload, opcode=0x1, mask=b"\x01\x02\x03\x04"):
    header = bytes([0x80 | opcode])
    length = len(payload)
    if length < 126:
        header += bytes([0x80 | length])
    elif length < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", length)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", length)
    return header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))


async def read_ws_frame(reader):
    first, second = await reader.readexactly(2)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack(">H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack(">Q", await reader.readexactly(8))[0]
    masked = second & 0x80
    mask = await reader.readexactly(4) if masked else None
    data = await reader.readexactly(length)
    if mask:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    return first & 0x0F, data


async def collect(dev, seconds):
    events = []

    def run():
        import time
        end = time.time() + seconds
        while time.time() < end:
            try:
                events.extend(dev.read())
            except BlockingIOError:
                time.sleep(0.005)

    await asyncio.to_thread(run)
    return events




async def main():
    proc = await asyncio.create_subprocess_exec(
        sys.executable, "-m", "phonepad.server",
        "--udp-port", str(UDP_PORT), "--ws-port", str(WS_PORT),
        "--http-port", str(HTTP_PORT),
        cwd=ROOT, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.STDOUT,
    )
    failures = []
    try:
        await asyncio.sleep(1.2)

        # ---- UDP: hello + state con cross y stick izquierdo ----
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setblocking(False)
        sock.sendto(json.dumps({"t": "hello", "name": "test-udp"}).encode(), UDP_ADDR)
        data, _ = await asyncio.wait_for(asyncio.get_running_loop().sock_recvfrom(sock, 2048), 2)
        welcome = json.loads(data)
        print("udp welcome:", welcome)
        if welcome.get("t") != "welcome" or welcome.get("slot") != 0:
            failures.append("udp welcome incorrecto")

        dev = find_pad()
        print("device:", dev.path, dev.name)
        task = asyncio.create_task(collect(dev, 1.0))
        for i in range(25):
            state = {"t": "state", "a": [0.5, -0.25, 0, 0, 0, 1], "b": 1}  # cross + L2 axis
            sock.sendto(json.dumps(state).encode(), UDP_ADDR)
            await asyncio.sleep(0.02)
        await asyncio.sleep(0.3)
        events = await task

        keys = [(ev.code, ev.value) for ev in events if ev.type == e.EV_KEY]
        axes = [(ev.code, ev.value) for ev in events if ev.type == e.EV_ABS]
        if (e.BTN_SOUTH, 1) not in keys:
            failures.append(f"no llegó cross: {keys[:6]}")
        if not any(c == e.ABS_X and v > 15000 for c, v in axes):
            failures.append(f"eje X incorrecto: {axes[:6]}")
        if not any(c == e.ABS_RZ and v == 255 for c, v in axes):
            failures.append(f"gatillo R2 incorrecto: {axes[:6]}")
        print("eventos:", len(events), "| cross ok:", (e.BTN_SOUTH, 1) in keys)

        # ---- WebSocket ----
        reader, writer = await asyncio.open_connection("127.0.0.1", WS_PORT)
        key = base64.b64encode(os.urandom(16)).decode()
        writer.write(
            (
                "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            ).encode()
        )
        await writer.drain()
        head = await reader.readuntil(b"\r\n\r\n")
        if b"101 Switching Protocols" not in head:
            failures.append(f"handshake WS falló: {head[:60]!r}")
        else:
            expect = base64.b64encode(
                hashlib.sha1((key + GUID).encode()).digest()
            ).decode()
            if expect.encode() not in head:
                failures.append("Sec-WebSocket-Accept incorrecto")

        writer.write(ws_frame(json.dumps({"t": "hello", "name": "test-ws"}).encode()))
        await writer.drain()
        opcode, payload = await asyncio.wait_for(read_ws_frame(reader), 2)
        msg = json.loads(payload)
        print("ws welcome:", msg)
        if opcode != 0x1 or msg.get("slot") != 1:
            failures.append(f"welcome WS inesperado: {opcode} {msg}")

        dev_ws = find_pad(1)
        print("device ws:", dev_ws.path, dev_ws.name)
        task = asyncio.create_task(collect(dev_ws, 0.8))
        for _ in range(15):
            writer.write(ws_frame(json.dumps({"t": "state", "a": [0, 0, 0, 0, 0, 0], "b": 1 << 1}).encode()))
            await writer.drain()
            await asyncio.sleep(0.03)
        await asyncio.sleep(0.2)
        events = await task
        keys = [(ev.code, ev.value) for ev in events if ev.type == e.EV_KEY]
        if (e.BTN_EAST, 1) not in keys:
            failures.append(f"ws: no llegó circle: {keys[:6]}")
        print("ws circle ok:", (e.BTN_EAST, 1) in keys)

        writer.close()
        # ---- TCP (USB con adb reverse) ----
        tcp_reader, tcp_writer = await asyncio.open_connection("127.0.0.1", UDP_PORT)
        tcp_writer.write((json.dumps({"t": "hello", "name": "test-tcp"}) + "\n").encode())
        await tcp_writer.drain()
        line = await asyncio.wait_for(tcp_reader.readline(), 2)
        msg = json.loads(line)
        print("tcp welcome:", msg)
        if msg.get("t") != "welcome" or msg.get("slot") is None:
            failures.append(f"welcome TCP inesperado: {msg}")

        dev_tcp = find_pad(msg["slot"])
        print("device tcp:", dev_tcp.path, dev_tcp.name)
        task = asyncio.create_task(collect(dev_tcp, 0.8))
        for _ in range(15):
            tcp_writer.write(
                (json.dumps({"t": "state", "a": [0, 0, 0, 0, 0, 0], "b": 1 << 3}) + "\n").encode()
            )
            await tcp_writer.drain()
            await asyncio.sleep(0.03)
        await asyncio.sleep(0.2)
        events = await task
        keys = [(ev.code, ev.value) for ev in events if ev.type == e.EV_KEY]
        if (e.BTN_NORTH, 1) not in keys:
            failures.append(f"tcp: no llegó triangle: {keys[:6]}")
        print("tcp triangle ok:", (e.BTN_NORTH, 1) in keys)
        tcp_writer.close()

    finally:
        proc.terminate()
        await proc.wait()

    if failures:
        print("\nFALLOS:")
        for f in failures:
            print(" -", f)
        return 1
    print("\nOK: UDP, WebSocket y mandos virtuales funcionan")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))

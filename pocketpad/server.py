"""Servidor PocketPad: UDP (app nativa) + WebSocket (navegador) + HTTP estático."""

import argparse
import asyncio
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .protocol import IDLE_TIMEOUT, MAX_PADS, welcome_msg

WEB_DIR = Path(__file__).parent / "web"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def pad_factory():
    """Backend según la plataforma: uinput en Linux, ViGEmBus en Windows."""
    if sys.platform.startswith("win"):
        from .pad_windows import WindowsPad

        return WindowsPad
    from .pad_linux import VirtualPad

    return VirtualPad


# ---------------------------------------------------------------- hub
class Hub:
    def __init__(self, factory, pin=""):
        self.factory = factory
        self.pin = str(pin or "")
        self.sessions = {}  # key -> dict(slot, name, pad, last, send)
        self.loop = None  # set in amain; el aviso de vibración llega de otro hilo

    def _used_slots(self):
        return {s["slot"] for s in self.sessions.values()}

    def _free_slot(self, want=None):
        used = self._used_slots()
        if isinstance(want, int) and 0 <= want < MAX_PADS and want not in used:
            return want
        for slot in range(MAX_PADS):
            if slot not in used:
                return slot
        return None

    async def handle(self, key, msg, send):
        kind = msg.get("t")
        if kind == "hello":
            if self.pin and str(msg.get("pin") or "") != self.pin:
                await send({"t": "error", "msg": "pin"})
                return
            session = self.sessions.get(key)
            if session:
                await send(welcome_msg(session["slot"], session["name"]))
                return
            slot = self._free_slot(msg.get("slot"))
            if slot is None:
                await send({"t": "full", "max": MAX_PADS})
                return
            name = str(msg.get("name") or f"phone-{slot + 1}")[:32]
            pad = self.factory(slot)
            try:
                pad.on_rumble = self._rumble_sender(slot)
            except AttributeError:
                pass
            self.sessions[key] = {
                "slot": slot,
                "name": name,
                "pad": pad,
                "last": time.monotonic(),
                "send": send,
            }
            log(f"+ slot {slot + 1} -> {name} ({key})")
            await send(welcome_msg(slot, name))
            return

        session = self.sessions.get(key)
        if not session:
            await send({"t": "error", "msg": "hello required"})
            return

        if kind == "state":
            axes = msg.get("a") or []
            try:
                axes = [float(v) for v in axes[:6]]
            except (TypeError, ValueError):
                axes = []
            axes += [0.0] * (6 - len(axes))
            try:
                mask = int(msg.get("b") or 0)
            except (TypeError, ValueError):
                mask = 0
            session["pad"].update(axes, mask)
            session["last"] = time.monotonic()
        elif kind == "touch":
            points = msg.get("p")
            if isinstance(points, list):
                try:
                    session["pad"].touch(points)
                except AttributeError:
                    pass
            session["last"] = time.monotonic()
        elif kind == "ping":
            await send({"t": "pong", "echo": msg.get("ts")})
        elif kind == "bye":
            await self.close(key)

    def _rumble_sender(self, slot):
        """Devuelve un aviso seguro entre hilos para el pad de ese slot."""

        def notify(amplitude, milliseconds):
            loop = self.loop
            if loop is None:
                return
            loop.call_soon_threadsafe(self._push_rumble, slot, amplitude, milliseconds)

        return notify

    def _push_rumble(self, slot, amplitude, milliseconds):
        for session in list(self.sessions.values()):
            if session["slot"] != slot:
                continue
            session["last"] = time.monotonic()
            message = {"t": "rumble", "amp": amplitude, "ms": milliseconds}
            asyncio.ensure_future(session["send"](message))

    async def close(self, key):
        session = self.sessions.pop(key, None)
        if not session:
            return
        log(f"- slot {session['slot'] + 1} ({session['name']}) liberado")
        session["pad"].close()

    async def watchdog(self):
        while True:
            await asyncio.sleep(1.0)
            now = time.monotonic()
            for key, session in list(self.sessions.items()):
                if now - session["last"] > IDLE_TIMEOUT:
                    await self.close(key)

    def shutdown(self):
        for session in self.sessions.values():
            session["pad"].close()
        self.sessions.clear()


# ---------------------------------------------------------------- UDP
class UdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, hub):
        self.hub = hub
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        try:
            msg = json.loads(data)
        except ValueError:
            return
        if not isinstance(msg, dict):
            return

        async def send(obj, _addr=addr):
            self.transport.sendto(json.dumps(obj).encode(), _addr)

        asyncio.ensure_future(self.hub.handle(("udp",) + tuple(addr), msg, send))

    def error_received(self, exc):
        log(f"udp error: {exc}")


# ---------------------------------------------------------------- WebSocket
def ws_frame(payload, opcode=0x1):
    header = bytes([0x80 | opcode])
    length = len(payload)
    if length < 126:
        header += bytes([length])
    elif length < 65536:
        header += bytes([126]) + struct.pack(">H", length)
    else:
        header += bytes([127]) + struct.pack(">Q", length)
    return header + payload


async def ws_read_frame(reader):
    first, second = await reader.readexactly(2)
    opcode = first & 0x0F
    masked = second & 0x80
    length = second & 0x7F
    if length == 126:
        length = struct.unpack(">H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack(">Q", await reader.readexactly(8))[0]
    mask = await reader.readexactly(4) if masked else None
    payload = await reader.readexactly(length) if length else b""
    if mask:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return opcode, payload


async def ws_handler(hub, reader, writer):
    peer = writer.get_extra_info("peername") or ("?", 0)
    key = ("ws",) + tuple(peer)
    try:
        head = await reader.readuntil(b"\r\n\r\n")
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError):
        writer.close()
        return

    lines = head.decode("latin-1").split("\r\n")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            name, value = line.split(":", 1)
            headers[name.strip().lower()] = value.strip()

    ws_key = headers.get("sec-websocket-key")
    if not ws_key or "websocket" not in headers.get("upgrade", "").lower():
        writer.write(b"HTTP/1.1 400 Bad Request\r\n\r\n")
        await writer.drain()
        writer.close()
        return

    accept = base64.b64encode(hashlib.sha1((ws_key + WS_GUID).encode()).digest()).decode()
    writer.write(
        (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode()
    )
    await writer.drain()

    async def send(obj):
        try:
            writer.write(ws_frame(json.dumps(obj).encode()))
            await writer.drain()
        except (ConnectionResetError, BrokenPipeError):
            pass

    try:
        while True:
            opcode, payload = await ws_read_frame(reader)
            if opcode == 0x8:
                break
            if opcode == 0x9:
                writer.write(ws_frame(payload, 0xA))
                await writer.drain()
                continue
            if opcode not in (0x1, 0x2):
                continue
            try:
                msg = json.loads(payload)
            except ValueError:
                continue
            if isinstance(msg, dict):
                await hub.handle(key, msg, send)
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    finally:
        await hub.close(key)
        try:
            writer.close()
        except Exception:
            pass


async def tcp_handler(hub, reader, writer):
    """Transporte TCP con líneas JSON: útil sobre USB con `adb reverse`."""
    peer = writer.get_extra_info("peername") or ("?", 0)
    key = ("tcp",) + tuple(peer)

    async def send(obj):
        try:
            writer.write((json.dumps(obj) + "\n").encode())
            await writer.drain()
        except (ConnectionResetError, BrokenPipeError):
            pass

    try:
        while True:
            line = await reader.readline()
            if not line:
                break
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if isinstance(msg, dict):
                await hub.handle(key, msg, send)
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        await hub.close(key)
        try:
            writer.close()
        except Exception:
            pass


# ---------------------------------------------------------------- HTTP
def start_http(port, directory):
    handler = partial(QuietHandler, directory=str(directory))
    server = ThreadingHTTPServer(("0.0.0.0", port), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass


def log(text):
    print(text, flush=True)


def local_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"


# ---------------------------------------------------------------- main
async def amain(args):
    hub = Hub(pad_factory(), args.pin)
    loop = asyncio.get_running_loop()
    hub.loop = loop

    await loop.create_datagram_endpoint(lambda: UdpProtocol(hub), local_addr=("0.0.0.0", args.udp_port))
    ws_server = await asyncio.start_server(
        lambda r, w: ws_handler(hub, r, w), "0.0.0.0", args.ws_port
    )
    tcp_server = await asyncio.start_server(
        lambda r, w: tcp_handler(hub, r, w), "0.0.0.0", args.udp_port
    )
    http_server = None
    if not args.no_http:
        http_server = start_http(args.http_port, WEB_DIR)

    watchdog = asyncio.create_task(hub.watchdog())
    ip = local_ip()
    log("PocketPad activo")
    log(f"  navegador : http://{ip}:{args.http_port}/")
    log(f"  udp/tcp   : {args.udp_port}  (app nativa; tcp para USB + adb reverse)")
    log(f"  ws        : {args.ws_port}   (navegador)")
    log(f"  mandos    : hasta {MAX_PADS} simultáneos")
    log(f"  PIN       : {'activado' if args.pin else 'sin PIN'}")

    try:
        await asyncio.Event().wait()
    finally:
        watchdog.cancel()
        ws_server.close()
        tcp_server.close()
        hub.shutdown()
        if http_server:
            http_server.shutdown()


def main():
    parser = argparse.ArgumentParser(description="PocketPad: DS4 virtual por red")
    parser.add_argument("--udp-port", type=int, default=9001)
    parser.add_argument("--ws-port", type=int, default=8099)
    parser.add_argument("--http-port", type=int, default=8080)
    parser.add_argument("--no-http", action="store_true", help="no servir la página web")
    parser.add_argument(
        "--pin",
        default=os.environ.get("POCKETPAD_PIN", ""),
        help="PIN obligatorio para conectar (vacío = red abierta)",
    )
    args = parser.parse_args()
    try:
        asyncio.run(amain(args))
    except KeyboardInterrupt:
        log("cerrado")


if __name__ == "__main__":
    main()

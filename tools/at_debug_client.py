#!/usr/bin/env python3
"""AndroidTerminal V3 — developer-only Debug client (AT.DEBUG-0).

Attaches to a Human-visible TerminalSession already open in the app via the
loopback DebugBridge (PC -> `adb forward tcp:45679 tcp:45679` -> device
127.0.0.1:45679). This is a developer transport only; it does NOT execute
terminal commands itself — the embedded ADB Engine executes.

NOT the AT.CLI. NOT an AT public protocol. NOT a product IPC.

Python standard library only (socket / json / base64 / argparse / threading).
"""

import argparse
import base64
import json
import socket
import sys
import threading

DEFAULT_PORT = 45679


def recv_lines(sock, buffer_holder):
    """Yield decoded JSON lines reassembled from the socket stream."""
    buf = buffer_holder[0]
    while True:
        if b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            buffer_holder[0] = buf
            if line.strip():
                yield line.decode("utf-8", "replace")
            continue
        try:
            chunk = sock.recv(4096)
        except Exception:
            return
        if not chunk:
            # flush any trailing line
            if buf.strip():
                yield buf.decode("utf-8", "replace")
            return
        buf += chunk


def pretty(kind, obj):
    t = obj.get("type", kind)
    if t == "hello":
        return "[HELLO] protocol=%s" % obj.get("protocol")
    if t == "sessions":
        out = ["[SESSIONS]"]
        for s in obj.get("sessions", []):
            out.append(
                "  #%s %s state=%s active=%s clients=%s"
                % (
                    s.get("sessionId"),
                    s.get("backendKind"),
                    s.get("state"),
                    s.get("isActivePage"),
                    s.get("attachedClientCount"),
                )
            )
        return "\n".join(out)
    if t == "attached":
        return "[ATTACHED] session=%s" % obj.get("sessionId")
    if t == "detached":
        return "[DETACHED] session=%s" % obj.get("sessionId")
    if t == "input":
        src = obj.get("source")
        text = obj.get("text", "")
        return "[INPUT %s] #=%s %r" % (src, obj.get("sessionId"), text)
    if t == "output":
        try:
            raw = base64.b64decode(obj.get("data", ""))
            return "[OUTPUT #%s] %s" % (obj.get("sessionId"), raw.decode("utf-8", "replace"))
        except Exception:
            return "[OUTPUT #%s] <bin>" % obj.get("sessionId")
    if t == "state":
        return "[STATE #%s] %s" % (obj.get("sessionId"), obj.get("state"))
    if t == "closed":
        return "[CLOSED] #=%s" % obj.get("sessionId")
    if t == "accepted":
        return "[ACCEPTED] #=%s" % obj.get("sessionId")
    if t == "rejected":
        return "[REJECTED] %s" % obj.get("message")
    if t == "error":
        return "[ERROR] %s" % obj.get("message")
    if t == "pong":
        return "[PONG]"
    return "[RAW] %s" % json.dumps(obj)


def send_line(sock, obj):
    sock.sendall((json.dumps(obj) + "\n").encode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description="AT Debug client (developer only)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("cmd", nargs="?", default="interactive",
                    help="status|list|attach|send-text|send-bytes|watch|detach|interactive")
    ap.add_argument("arg", nargs="?", default=None, help="sessionId or text")
    args = ap.parse_args()

    sock = socket.create_connection(("127.0.0.1", args.port), timeout=10)
    sock.settimeout(None)

    # Start a reader thread that tails the stream (for interactive/watch).
    buffer_holder = [b""]
    stop = threading.Event()

    def reader():
        for line in recv_lines(sock, buffer_holder):
            if stop.is_set():
                break
            try:
                obj = json.loads(line)
                print(pretty(line, obj))
                sys.stdout.flush()
            except Exception:
                print("[RAW] %s" % line)
                sys.stdout.flush()
        if not stop.is_set():
            print("[DISCONNECTED]")
            sys.stdout.flush()

    if args.cmd in ("interactive", "watch"):
        threading.Thread(target=reader, daemon=True).start()

    if args.cmd == "status":
        # Just show what the server sends on connect (hello) then exit.
        sock.settimeout(3)
        try:
            line = buffer_holder[0]
            while b"\n" not in line:
                line += sock.recv(4096)
            print(pretty("hello", json.loads(line.split(b"\n")[0])))
        except Exception as e:
            print("status: %s" % e)
        return
    if args.cmd == "list":
        threading.Event().wait(0.1)
        send_line(sock, {"type": "list"})
        threading.Event().wait(2)
        return
    if args.cmd == "attach":
        send_line(sock, {"type": "attach", "sessionId": args.arg or "active"})
        threading.Event().wait(1)
        return
    if args.cmd == "send-text":
        sid = int(args.arg) if args.arg and args.arg.isdigit() else 1
        text = sys.argv[-1] if len(sys.argv) > 3 else ""
        send_line(sock, {"type": "write", "sessionId": sid, "text": text})
        threading.Event().wait(1)
        return
    if args.cmd == "send-bytes":
        sid = int(args.arg) if args.arg and args.arg.isdigit() else 1
        b64 = sys.argv[-1] if len(sys.argv) > 3 else ""
        send_line(sock, {"type": "write_raw", "sessionId": sid, "base64": b64})
        threading.Event().wait(1)
        return
    if args.cmd == "detach":
        send_line(sock, {"type": "detach"})
        threading.Event().wait(1)
        return

    # interactive: read stdin commands, tail the stream.
    print("AT debug client (interactive). Commands: list / attach <id|active> / "
          "send-text <id> <text> / send-bytes <id> <base64> / detach / quit")
    try:
        while True:
            try:
                line = input("> ")
            except EOFError:
                break
            line = line.strip()
            if not line:
                continue
            if line in ("quit", "exit"):
                break
            parts = line.split(None, 2)
            if parts[0] == "list":
                send_line(sock, {"type": "list"})
            elif parts[0] == "attach":
                send_line(sock, {"type": "attach", "sessionId": parts[1] if len(parts) > 1 else "active"})
            elif parts[0] == "send-text":
                sid = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 1
                text = parts[2] if len(parts) > 2 else ""
                send_line(sock, {"type": "write", "sessionId": sid, "text": text})
            elif parts[0] == "send-bytes":
                sid = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 1
                b64 = parts[2] if len(parts) > 2 else ""
                send_line(sock, {"type": "write_raw", "sessionId": sid, "base64": b64})
            elif parts[0] == "detach":
                send_line(sock, {"type": "detach"})
            elif parts[0] == "ping":
                send_line(sock, {"type": "ping"})
            else:
                print("unknown command: %s" % parts[0])
    finally:
        stop.set()
        try:
            sock.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()

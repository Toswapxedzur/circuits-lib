#!/usr/bin/env python3
"""Talk to the running CircuitsLib 3D editor's LIVE CONSOLE (launch the app with -Pconsole=1 → 127.0.0.1:4711).

Usage:
  live_console.py 'ls physEditor' 'get physEditor.yawDeg' 'set physEditor.yawDeg 90'   # one reply line per command
  live_console.py -p 4711 -                                                             # commands from stdin
  live_console.py --raw ...                                                             # print the JSON reply as-is
Commands: help | roots | ls [path] | get path | set path value | call path.method(args) | watch path everyMs count |
probe name | probes | and any InputScript line (key/hold/release/lmb/rmb/scroll/look/do/expect/dump/wait).
"""
import json, socket, sys

def main(argv):
    port, raw, cmds = 4711, False, []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "-p": port = int(argv[i + 1]); i += 2; continue
        if a == "--raw": raw = True; i += 1; continue
        cmds.append(a); i += 1
    if cmds == ["-"] or not cmds:
        cmds = [l.rstrip("\n") for l in sys.stdin if l.strip()]
    with socket.create_connection(("127.0.0.1", port), timeout=90) as s:
        f = s.makefile("rw", encoding="utf-8", newline="\n")
        bad = 0
        for c in cmds:
            f.write(c + "\n"); f.flush()
            reply = f.readline()
            if not reply:
                print(f"> {c}\n  (connection closed)"); bad += 1; break
            if raw:
                print(reply.rstrip()); continue
            try:
                j = json.loads(reply)
            except json.JSONDecodeError:
                print(f"> {c}\n  {reply.rstrip()}"); continue
            print(f"> {c}" + ("" if j.get("ok") else "   [!!]"))
            for line in j.get("lines", []):
                print("  " + line)
            if not j.get("ok"): bad += 1
        return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

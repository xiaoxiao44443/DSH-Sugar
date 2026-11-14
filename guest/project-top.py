#!/usr/bin/python3
"""Live CPU and resident memory for processes visible in this Sugar workspace."""
import argparse
import math
import os
import select
import shutil
import signal
import sys
import termios
import time
import tty
from typing import NamedTuple


class Process(NamedTuple):
    name: str
    state: str
    ticks: int
    start: int
    rss_kib: int


def snapshot(pids, page_kib):
    result = {}
    for entry in os.listdir("/proc"):
        if not entry.isdecimal():
            continue
        pid = int(entry)
        if pids and pid not in pids:
            continue
        try:
            with open(f"/proc/{pid}/stat", "rb") as stat:
                line = stat.read()
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
        # comm may contain spaces and closing parentheses. Fields after its final
        # delimiter begin at Linux field 3 (state).
        left, right = line.index(b"("), line.rindex(b")")
        fields = line[right + 1:].split()
        name = line[left + 1:right].decode("utf-8", "replace")
        result[pid] = Process("".join(c if c.isprintable() else "?" for c in name),
                              fields[0].decode(), int(fields[11]) + int(fields[12]),
                              int(fields[19]), int(fields[21]) * page_kib)
    return time.monotonic(), result


def run(args, pids):
    interactive = not args.b and sys.stdout.isatty() and sys.stdin.isatty()
    count = args.n if args.n is not None else (None if interactive or args.b else 1)
    hz = os.sysconf("SC_CLK_TCK")
    page_kib = os.sysconf("SC_PAGE_SIZE") // 1024
    stdin_mode = None
    try:
        if interactive:
            stdin_mode = termios.tcgetattr(0)
            tty.setcbreak(0, termios.TCSANOW)
            sys.stdout.write("\x1b[?1049h\x1b[?25l")
        stamp, previous = snapshot(pids, page_kib)
        # CPU is a delta, including on the first displayed frame.
        delay = min(args.d, 0.2)
        while count is None or count > 0:
            if interactive:
                if select.select([0], [], [], delay)[0]:
                    if os.read(0, 1) in (b"", b"q", b"Q"):
                        break
            else:
                time.sleep(delay)
            now, processes = snapshot(pids, page_kib)
            elapsed = now - stamp
            rows = []
            for pid, proc in processes.items():
                old = previous.get(pid)
                cpu = None
                if old and old.start == proc.start and elapsed > 0:
                    cpu = (proc.ticks - old.ticks) * 100.0 / (hz * elapsed)
                rows.append((pid, proc, cpu))
            rows.sort(key=lambda row: (-(row[2] if row[2] is not None else -1), row[0]))
            lines = [f"Workspace processes: {len(rows)}   CPU: % of one core",
                     "    PID  S    CPU%  RSS(KiB)  COMMAND"]
            for pid, proc, cpu in rows:
                usage = "-" if cpu is None else f"{cpu:.1f}"
                lines.append(f"{pid:7d}  {proc.state}  {usage:>6}  {proc.rss_kib:8d}  {proc.name}")
            if interactive:
                size = shutil.get_terminal_size()
                lines = [s[:size.columns] for s in lines[:max(1, size.lines - 1)]]
                sys.stdout.write("\x1b[H" + "\n".join(s + "\x1b[K" for s in lines) + "\x1b[J")
            else:
                sys.stdout.write("\n".join(lines) + "\n")
            sys.stdout.flush()
            if count is not None:
                count -= 1
            stamp, previous, delay = now, processes, args.d
    finally:
        if stdin_mode is not None:
            termios.tcsetattr(0, termios.TCSANOW, stdin_mode)
            sys.stdout.write("\x1b[?25h\x1b[?1049l")
            sys.stdout.flush()


def main():
    parser = argparse.ArgumentParser(prog="top", description=__doc__,
                                     epilog="Press q to quit. Without a terminal, top prints one snapshot by default.")
    parser.add_argument("-b", action="store_true", help="batch mode")
    parser.add_argument("-n", type=int, metavar="COUNT", help="number of snapshots")
    parser.add_argument("-d", type=float, default=1.0, metavar="SECONDS", help="refresh interval")
    parser.add_argument("-p", action="append", default=[], metavar="PID[,PID]", help="limit to these processes")
    args = parser.parse_args()
    if args.n is not None and args.n < 1:
        parser.error("COUNT must be positive")
    if not math.isfinite(args.d) or args.d <= 0:
        parser.error("SECONDS must be positive and finite")
    parts = [part for group in args.p for part in group.split(",")]
    if any(not part.isascii() or not part.isdecimal() or int(part) <= 0 for part in parts):
        parser.error("expected a positive PID")
    def stop(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGHUP, stop)
    try:
        run(args, {int(part) for part in parts})
    except KeyboardInterrupt:
        return 130
    except BrokenPipeError:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())

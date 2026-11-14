#!/opt/sugar/mobile-python/bin/python3.11 -I
"""Send ICMP Echo requests through an unprivileged Linux ping socket."""
import argparse
import math
import os
import select
import socket
import struct
import sys
import time


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0 or number > 2**30:
        raise argparse.ArgumentTypeError("expected a positive number of seconds")
    return number


def checksum(data):
    if len(data) % 2:
        data += b"\0"
    total = sum(word for (word,) in struct.iter_unpack("!H", data))
    while total >> 16:
        total = (total & 0xffff) + (total >> 16)
    return ~total & 0xffff


def ping(args, family, address, sock):
    v4 = family == socket.AF_INET
    request_type, reply_type = (8, 0) if v4 else (128, 129)
    reply_ip = socket.inet_pton(family, address[0])
    start = time.monotonic()
    deadline = start + args.w if args.w else math.inf
    sent = received = 0
    minimum, maximum, total = math.inf, 0, 0
    print(f"PING {args.host} ({address[0]}): {args.s} data bytes", flush=True)
    try:
        while (args.c is None or sent < args.c) and time.monotonic() < deadline:
            seq = (sent + 1) & 0xffff
            # Verify the complete echo payload as well as address and sequence.
            payload = os.urandom(args.s)
            header = struct.pack("!BBHHH", request_type, 0, 0, 0, seq)
            check = checksum(header + payload) if v4 else 0
            packet = struct.pack("!BBHHH", request_type, 0, check, 0, seq) + payload
            began = time.monotonic()
            sock.sendto(packet, address)
            sent += 1
            # Linux ping sockets assign the identifier, even when the packet contains zero.
            identifier = sock.getsockname()[1]
            expires = min(deadline, began + args.W)
            while True:
                remaining = expires - time.monotonic()
                if remaining <= 0 or not select.select([sock], [], [], remaining)[0]:
                    break
                reply, remote = sock.recvfrom(65535)
                if len(reply) != len(packet) or socket.inet_pton(family, remote[0]) != reply_ip:
                    continue
                kind, code, _, ident, number = struct.unpack_from("!BBHHH", reply)
                if (kind, code, ident, number) != (reply_type, 0, identifier, seq) or reply[8:] != payload:
                    continue
                elapsed = (time.monotonic() - began) * 1000
                received += 1
                minimum, maximum, total = min(minimum, elapsed), max(maximum, elapsed), total + elapsed
                if not args.q:
                    print(f"{len(reply)} bytes from {remote[0]}: icmp_seq={seq} time={elapsed:.3f} ms", flush=True)
                break
            if args.c is None or sent < args.c:
                pause = min(deadline, began + args.i) - time.monotonic()
                if pause > 0:
                    time.sleep(pause)
    except KeyboardInterrupt:
        pass
    print(f"\n--- {args.host} ping statistics ---")
    loss = 100 * (sent - received) / sent if sent else 0
    print(f"{sent} packets transmitted, {received} received, {loss:g}% packet loss")
    if received:
        print(f"rtt min/avg/max = {minimum:.3f}/{total/received:.3f}/{maximum:.3f} ms")
    return 0 if received and not (args.w and args.c and received < args.c) else 1


def main():
    parser = argparse.ArgumentParser(prog="ping", description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("-4", dest="family", action="store_const", const=socket.AF_INET)
    group.add_argument("-6", dest="family", action="store_const", const=socket.AF_INET6)
    parser.add_argument("-c", type=int, metavar="COUNT", help="stop after sending COUNT requests")
    parser.add_argument("-W", type=positive, default=1.0, metavar="SECONDS", help="time to wait for each reply")
    parser.add_argument("-w", type=positive, metavar="SECONDS", help="overall deadline after name resolution")
    parser.add_argument("-i", type=positive, default=1.0, metavar="SECONDS", help="interval between requests")
    parser.add_argument("-s", type=int, default=56, metavar="BYTES", help="size of the echo payload")
    parser.add_argument("-q", action="store_true", help="print only the summary")
    parser.add_argument("-n", action="store_true", help="print numeric reply addresses (default)")
    parser.add_argument("host")
    args = parser.parse_args()
    if args.c is not None and args.c < 1:
        parser.error("COUNT must be at least one")
    if not 0 <= args.s <= 65507:
        parser.error("payload must be between 0 and 65507 bytes")
    try:
        results = socket.getaddrinfo(args.host, 0, args.family or socket.AF_UNSPEC, socket.SOCK_DGRAM)
        family, _, _, _, address = results[0]
        protocol = socket.IPPROTO_ICMP if family == socket.AF_INET else socket.IPPROTO_ICMPV6
        with socket.socket(family, socket.SOCK_DGRAM, protocol) as sock:
            return ping(args, family, address, sock)
    except OSError as error:
        print(f"ping: {args.host}: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

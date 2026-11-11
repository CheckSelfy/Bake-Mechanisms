#!/usr/bin/env python3
"""Offline review clients for pinned Minecraft 26.3 protocol 777.

IDs come from the pinned Mojang server's net.minecraft.data.Main --reports packets.json.
Idle mode handles login/configuration, teleport/chunk acknowledgement and keepalive.
Opt-in --control-dir sends review commands, block/container/movement inputs and records
received system messages/action bars. It provides no claim of rendered UI coverage.
"""
import hashlib
import importlib.util
import argparse
import io
import json
from pathlib import Path
import socket
import struct
import threading
import uuid
import zlib

NBT_SPEC = importlib.util.spec_from_file_location('review_nbt', Path(__file__).with_name('extract-farm-structures.py'))
NBT_MODULE = importlib.util.module_from_spec(NBT_SPEC)
NBT_SPEC.loader.exec_module(NBT_MODULE)


def block_position(x, y, z):
    return struct.pack('>Q', ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff))


def control_packet(parts, sequence):
    action, *args = parts
    if action == 'command':
        return 7, string(args[0])
    if action == 'left':
        return 41, varint(0) + block_position(*map(int, args)) + b'\x01' + varint(sequence)
    if action == 'right':
        # Main hand, block position, UP face, relative hit vector, inside/miss flags, sequence.
        return 66, varint(0) + block_position(*map(int, args)) + varint(1) + struct.pack('>fff??', .5, 1., .5, False, False) + varint(sequence)
    if action == 'sneak':
        return 43, bytes([0x20 if args[0] == 'true' else 0])
    if action == 'move':
        return 30, struct.pack('>dddB', *map(float, args), 1)
    if action == 'slot':
        return 54, struct.pack('>h', int(args[0]))
    if action == 'close':
        return 19, varint(int(args[0]))
    if action == 'shift_click':
        container, state, slot = map(int, args)
        return 18, varint(container) + varint(state) + struct.pack('>hb', slot, 0) + varint(1) + b'\0\0'
    raise ValueError(f'Unknown review action {action}')


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_varint(stream):
    value = 0
    for shift in range(0, 35, 7):
        byte = stream.read(1)
        if not byte:
            raise EOFError('connection closed')
        value |= (byte[0] & 127) << shift
        if not byte[0] & 128:
            return value
    raise ValueError('oversized varint')


def string(value):
    data = value.encode('utf-8')
    return varint(len(data)) + data


def connect(username, command=None, port=25566, control_dir=None):
    with socket.create_connection(('127.0.0.1', port), timeout=30) as sock:
        stream = sock.makefile('rb')
        compressed = False
        send_lock = threading.Lock()
        ready = threading.Event()
        open_container = 0
        container_states = {}

        def send(packet_id, data=b''):
            packet = varint(packet_id) + data
            # Uncompressed packets below the negotiated threshold are valid in compression mode.
            if compressed:
                packet = b'\0' + packet
            with send_lock:
                sock.sendall(varint(len(packet)) + packet)

        def controls():
            acknowledged = control_dir / f'{username}.ack'
            last = int(acknowledged.read_text(encoding='utf-8')) if acknowledged.exists() else 0
            ready.wait()
            while sock.fileno() != -1:
                requests = sorted(control_dir.glob(f'{username}-*.request'), key=lambda p: int(p.stem.rsplit('-', 1)[1]))
                for request in requests:
                    number = int(request.stem.rsplit('-', 1)[1])
                    if number <= last:
                        continue
                    try:
                        parts = request.read_text(encoding='utf-8').strip().split('\t')
                    except OSError:
                        break  # A Windows rename can temporarily deny a concurrent read.
                    if parts[0] == 'shift_click':
                        parts = ['shift_click', str(open_container), str(container_states.get(open_container, 0)), parts[1]]
                    send(*control_packet(parts, number))
                    send(13)  # client_tick_end: protocol 777 allows only one position update per client tick.
                    (control_dir / f'{username}.ack').write_text(str(number), encoding='utf-8')
                    last = number
                threading.Event().wait(.02)

        if control_dir is not None:
            threading.Thread(target=controls, daemon=True).start()

        send(0, varint(777) + string('127.0.0.1') + struct.pack('>H', port) + varint(2))
        offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + username).encode()).digest(), version=3)
        send(0, string(username) + offline.bytes)
        state = 'login'
        position = (0.0, 0.0, 0.0, 0.0, 0.0)
        while True:
            length = read_varint(stream)
            data = stream.read(length)
            if len(data) != length:
                raise EOFError('incomplete packet')
            packet = io.BytesIO(data)
            if compressed:
                inflated_length = read_varint(packet)
                packet = io.BytesIO(zlib.decompress(packet.read()) if inflated_length else packet.read())
            packet_id = read_varint(packet)
            payload = packet.read()
            if state == 'login':
                if packet_id == 3:
                    compressed = True
                elif packet_id == 2:
                    send(3)
                    state = 'configuration'
                elif packet_id == 0:
                    raise RuntimeError(f'login refused: {payload!r}')
            elif state == 'configuration':
                if packet_id == 15:
                    send(7, varint(0))  # No cached known packs; server sends registry data.
                elif packet_id == 20:
                    send(9)  # Accept server code of conduct on this isolated server.
                elif packet_id == 3:
                    send(3)
                    state = 'play'
                elif packet_id == 4:
                    send(4, payload)
                elif packet_id == 5:
                    send(5, payload)
                elif packet_id == 2:
                    raise RuntimeError(f'configuration refused: {payload!r}')
            else:
                if packet_id == 50:
                    send(44)
                    print('REVIEW_CLIENT_JOINED', username, flush=True)
                    ready.set()
                    if command is not None:
                        send(7, string(command))  # Protocol 777 unsigned chat command, without leading slash.
                        command = None
                elif packet_id == 73:
                    move = io.BytesIO(payload)
                    teleport = read_varint(move)
                    xyz = struct.unpack('>ddd', move.read(24))
                    move.read(24)  # Delta movement is irrelevant for stationary review clients.
                    rotation = struct.unpack('>ff', move.read(8))
                    flags = struct.unpack('>i', move.read(4))[0]
                    position = tuple(value + old if flags & (1 << i) else value
                                     for i, (value, old) in enumerate(zip(xyz + rotation, position)))
                    send(0, varint(teleport) + struct.pack('>dddff', *position))
                    send(44)
                elif packet_id == 45:
                    send(28, payload)
                elif packet_id == 62:
                    send(45, payload)
                elif packet_id == 11:
                    send(11, struct.pack('>f', 64.0))
                elif packet_id == 120:
                    send(16)
                    state = 'configuration'
                elif packet_id == 32:
                    print(username, 'server disconnected client', flush=True)
                    return
                elif control_dir is not None and packet_id in (89, 124):
                    nbt = NBT_MODULE.NBT(payload)
                    component = nbt.value(nbt.number('B'))  # Network NBT has no root name.
                    with (control_dir / f'{username}.messages').open('a', encoding='utf-8') as messages:
                        messages.write(json.dumps(component, ensure_ascii=False) + '\n')
                elif control_dir is not None and packet_id == 60:
                    screen = read_varint(io.BytesIO(payload))
                    open_container = screen
                    (control_dir / f'{username}.screen').write_text(str(screen), encoding='utf-8')
                elif control_dir is not None and packet_id in (18, 20):
                    header = io.BytesIO(payload)
                    container = read_varint(header)
                    container_states[container] = read_varint(header)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=25566)
    parser.add_argument('--control-dir', type=Path)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('port must be between 1 and 65535')
    errors = []

    def client(username):
        while True:
            failure = None
            try:
                connect(username, port=args.port, control_dir=args.control_dir)
            except Exception as error:
                failure = error
            expected = args.control_dir / f'{username}.reconnect' if args.control_dir else None
            if expected is not None and expected.exists():
                expected.unlink()
                print('REVIEW_CLIENT_EXPECTED_RECONNECT', username, flush=True)
                continue
            if failure is not None:
                errors.append(failure)
                print(username, type(failure).__name__, str(failure), flush=True)
            return

    threads = [threading.Thread(target=client, args=(name,)) for name in ('ReviewCreator', 'ReviewOther')]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())

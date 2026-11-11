#!/usr/bin/env python3
"""Minimal idle offline clients for pinned Minecraft 26.3 protocol 777.

IDs come from the pinned Mojang server's net.minecraft.data.Main --reports packets.json.
Only login/configuration, teleport acknowledgement, chunk acknowledgement, and keepalive
are implemented. This intentionally provides no claim of rendered UI/input coverage.
"""
import hashlib
import io
import socket
import struct
import threading
import uuid
import zlib


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


def connect(username, command=None):
    with socket.create_connection(('127.0.0.1', 25566), timeout=30) as sock:
        stream = sock.makefile('rb')
        compressed = False

        def send(packet_id, data=b''):
            packet = varint(packet_id) + data
            # Uncompressed packets below the negotiated threshold are valid in compression mode.
            if compressed:
                packet = b'\0' + packet
            sock.sendall(varint(len(packet)) + packet)

        send(0, varint(777) + string('127.0.0.1') + struct.pack('>H', 25566) + varint(2))
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


def main():
    errors = []

    def client(username):
        try:
            connect(username)
        except Exception as error:
            errors.append(error)
            print(username, type(error).__name__, str(error), flush=True)

    threads = [threading.Thread(target=client, args=(name,)) for name in ('ReviewCreator', 'ReviewOther')]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())

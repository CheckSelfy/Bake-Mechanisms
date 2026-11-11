#!/usr/bin/env python3
"""Read selected Anvil cuboids into vanilla structure NBT. Never writes the source world.

The checked-in manifest records the selection; running this needs the original local map.
Python standard library only. Blocks and block entities are retained; these two selections
have no persistent mechanism entities. Nearby players, item frames and unrelated builds
are intentionally outside the selected cuboids.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
NUMBERS = {1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}


class NBT:
    def __init__(self, data=b''):
        self.stream = io.BytesIO(data)

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.stream.read(struct.calcsize(fmt)))[0]

    def text(self):
        # Names/block IDs in the selected compounds are standard UTF-8; unknown string
        # payloads use surrogateescape so original bytes survive without interpretation.
        return self.stream.read(self.number('H')).decode('utf-8', errors='surrogateescape')

    def value(self, tag):
        if tag in NUMBERS:
            return self.number(NUMBERS[tag])
        if tag == 7:
            return self.stream.read(self.number('i'))
        if tag == 8:
            return self.text()
        if tag == 9:
            subtype, length = self.number('B'), self.number('i')
            return subtype, [self.value(subtype) for _ in range(length)]
        if tag == 10:
            result = {}
            while (subtype := self.number('B')) != 0:
                name = self.text()
                result[name] = (subtype, self.value(subtype))
            return result
        if tag in (11, 12):
            return [self.number('i' if tag == 11 else 'q') for _ in range(self.number('i'))]
        raise ValueError(f'Unsupported NBT tag {tag}')

    def root(self):
        tag = self.number('B')
        self.text()
        return self.value(tag)


def encode_value(tag, value):
    if tag in NUMBERS:
        return struct.pack('>' + NUMBERS[tag], value)
    if tag == 8:
        data = value.encode('utf-8', errors='surrogateescape')
        return struct.pack('>H', len(data)) + data
    if tag == 7:
        return struct.pack('>i', len(value)) + value
    if tag == 9:
        subtype, entries = value
        return struct.pack('>Bi', subtype, len(entries)) + b''.join(encode_value(subtype, v) for v in entries)
    if tag == 10:
        return b''.join(bytes([t]) + encode_value(8, name) + encode_value(t, v)
                        for name, (t, v) in value.items()) + b'\0'
    if tag in (11, 12):
        return struct.pack('>i', len(value)) + b''.join(struct.pack('>i' if tag == 11 else '>q', v) for v in value)
    raise ValueError(f'Unsupported NBT tag {tag}')


def get(compound, key, default=None):
    return compound[key][1] if key in compound else default


class World:
    def __init__(self, root):
        self.root = root
        self.regions = {}
        self.chunks = {}
        self.hashes = {}

    def chunk(self, cx, cz):
        if (cx, cz) in self.chunks:
            return self.chunks[cx, cz]
        path = self.root / 'region' / f'r.{cx // 32}.{cz // 32}.mca'
        if path not in self.regions:
            self.regions[path] = path.read_bytes()
            self.hashes[path.name] = hashlib.sha256(self.regions[path]).hexdigest()
        region = self.regions[path]
        index = (cz % 32) * 32 + cx % 32
        offset = int.from_bytes(region[index * 4:index * 4 + 3], 'big') * 4096
        if not offset:
            raise ValueError(f'Selected chunk missing: {cx}, {cz}')
        length = struct.unpack('>I', region[offset:offset + 4])[0]
        compression = region[offset + 4]
        data = region[offset + 5:offset + 4 + length]
        if compression == 2:
            data = zlib.decompress(data)
        elif compression == 1:
            data = gzip.decompress(data)
        elif compression != 3:
            raise ValueError(f'Unsupported Anvil compression {compression}')
        chunk = NBT(data).root()
        self.chunks[cx, cz] = chunk
        return chunk

    def block(self, x, y, z):
        chunk = self.chunk(x // 16, z // 16)
        sections = get(chunk, 'sections', (10, []))[1]
        section = next((s for s in sections if get(s, 'Y') == y // 16), {})
        states = get(section, 'block_states', {})
        palette = get(states, 'palette', (10, [{'Name': (8, 'minecraft:air')}]))[1]
        if len(palette) == 1:
            return palette[0]
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        index = (y % 16) * 256 + (z % 16) * 16 + x % 16
        packed = get(states, 'data')[index // per_long] & ((1 << 64) - 1)
        return palette[(packed >> (index % per_long * bits)) & ((1 << bits) - 1)]


def extract(world, farm, destination):
    low, high = farm['source_min'], farm['source_max']
    size = [b - a + 1 for a, b in zip(low, high)]
    if any(n < 1 for n in size):
        raise ValueError('Invalid cuboid')
    tiles = {}
    for cx in range(low[0] // 16, high[0] // 16 + 1):
        for cz in range(low[2] // 16, high[2] // 16 + 1):
            for tile in get(world.chunk(cx, cz), 'block_entities', (10, []))[1]:
                position = tuple(get(tile, axis) for axis in ('x', 'y', 'z'))
                tiles[position] = tile
    palette, indices, blocks = [], {}, []
    counts = {}
    for y in range(low[1], high[1] + 1):
        for z in range(low[2], high[2] + 1):
            for x in range(low[0], high[0] + 1):
                state = world.block(x, y, z)
                name = get(state, 'Name')
                counts[name] = counts.get(name, 0) + 1
                key = encode_value(10, state)
                if key not in indices:
                    indices[key] = len(palette)
                    palette.append(state)
                block = {'pos': (9, (3, [x - low[0], y - low[1], z - low[2]])), 'state': (3, indices[key])}
                if (x, y, z) in tiles:
                    tile = copy.deepcopy(tiles[x, y, z])
                    for axis in ('x', 'y', 'z'):
                        tile.pop(axis, None)
                    block['nbt'] = (10, tile)
                blocks.append(block)
    version = get(world.chunk(low[0] // 16, low[2] // 16), 'DataVersion')
    structure = {'DataVersion': (3, version), 'size': (9, (3, size)), 'palette': (9, (10, palette)),
                 'blocks': (9, (10, blocks)), 'entities': (9, (10, []))}
    payload = gzip.compress(b'\x0a\x00\x00' + encode_value(10, structure), mtime=0)
    destination.write_bytes(payload)
    return {'file': destination.name, 'sha256': hashlib.sha256(payload).hexdigest(),
            'source_data_version': version, 'size': size, 'block_counts': counts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'test-worlds' / 'ppl9' / 'farms.json')
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    folder = args.manifest.parent / 'structures'
    folder.mkdir(parents=True, exist_ok=True)
    world = World(args.source.resolve())
    output = {}
    for farm in manifest['farms']:
        template = farm.get('template', farm['id'])
        if template not in output:
            output[template] = extract(world, farm, folder / (template + '.nbt'))
    provenance = {'source_name': args.source.name, 'regions_sha256': world.hashes, 'structures': output}
    (args.manifest.parent / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    for name, item in output.items():
        print(name, item['size'], 'cactus=', item['block_counts'].get('minecraft:cactus', 0),
              'cane=', item['block_counts'].get('minecraft:sugar_cane', 0), item['sha256'])


if __name__ == '__main__':
    main()

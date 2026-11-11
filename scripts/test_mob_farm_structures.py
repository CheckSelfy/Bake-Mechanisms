"""Validate the actual imported mob-farm assets and unchanged native spawn templates."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('mob_nbt', ROOT / 'scripts/extract-farm-structures.py')
NBT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NBT)


class MobFarmStructureTests(unittest.TestCase):
    def test_real_spawners_and_collection_are_preserved_without_injected_equipment(self):
        folder = ROOT / 'test-worlds/ppl9-mobs'
        manifest = json.loads((folder / 'farms.json').read_text(encoding='utf-8'))
        provenance = json.loads((folder / 'provenance.json').read_text(encoding='utf-8'))
        self.assertEqual({farm['id'] for farm in manifest['farms']}, {'skeleton', 'zombie'})
        for farm in manifest['farms']:
            with self.subTest(farm=farm['id']):
                raw = (folder / 'structures' / (farm['id'] + '.nbt')).read_bytes()
                self.assertEqual(hashlib.sha256(raw).hexdigest(), provenance['structures'][farm['id']]['sha256'])
                root = NBT.NBT(gzip.decompress(raw)).root()
                self.assertEqual(NBT.get(root, 'size')[1], farm['size'])
                self.assertEqual(NBT.get(root, 'DataVersion'), 4082)
                self.assertEqual(NBT.get(root, 'entities')[1], [])
                blocks = NBT.get(root, 'blocks')[1]
                tiles = [(NBT.get(block, 'pos')[1], NBT.get(block, 'nbt')) for block in blocks if 'nbt' in block]
                expected_position = [v - start for v, start in zip(farm['spawner'], farm['destination'])]
                spawner = next(tile for position, tile in tiles if position == expected_position)
                self.assertEqual(NBT.get(spawner, 'id'), 'minecraft:mob_spawner')
                template = NBT.get(NBT.get(spawner, 'SpawnData'), 'entity')
                self.assertEqual(NBT.get(template, 'id'), 'minecraft:' + farm['id'])
                self.assertEqual(set(template), {'id'}, 'Spawner must use native equipment generation, not supplied armor')
                self.assertTrue(any('hopper' in NBT.get(tile, 'id', '') for _, tile in tiles))
                self.assertTrue(any('chest' in NBT.get(tile, 'id', '') for _, tile in tiles))

    def test_imported_farms_have_separate_chunks_and_adjacent_external_outputs(self):
        farms = json.loads((ROOT / 'test-worlds/ppl9-mobs/farms.json').read_text(encoding='utf-8'))['farms']
        occupied = set()
        for farm in farms:
            low = farm['destination']
            high = [a + n - 1 for a, n in zip(low, farm['size'])]
            chunks = {(x, z) for x in range(low[0] // 16, high[0] // 16 + 1)
                      for z in range(low[2] // 16, high[2] // 16 + 1)}
            self.assertTrue(occupied.isdisjoint(chunks))
            occupied |= chunks
            output = farm['output']
            self.assertFalse(all(a <= v <= b for a, v, b in zip(low, output, high)))
            self.assertTrue(all(a - 1 <= v <= b + 1 for a, v, b in zip(low, output, high)))


if __name__ == '__main__':
    unittest.main()

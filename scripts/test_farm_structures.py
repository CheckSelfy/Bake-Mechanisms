"""Checks for the real extracted NBT fixtures and negative-coordinate palette decoding."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('farm_structures', Path(__file__).with_name('extract-farm-structures.py'))
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class FarmStructureTests(unittest.TestCase):
    def test_real_assets_preserve_dimensions_plants_block_entities_and_hashes(self):
        folder = ROOT / 'test-worlds/ppl9'
        manifest = json.loads((folder / 'farms.json').read_text())
        provenance = json.loads((folder / 'provenance.json').read_text())
        expected_plants = {'cactus': ('minecraft:cactus', 32), 'cane': ('minecraft:sugar_cane', 27)}
        for farm in manifest['farms']:
            with self.subTest(farm=farm['id']):
                template = farm.get('template', farm['id'])
                data = (folder / 'structures' / (template + '.nbt')).read_bytes()
                structure = TOOL.NBT(gzip.decompress(data)).root()
                self.assertEqual(TOOL.get(structure, 'DataVersion'), 4082)
                self.assertEqual(TOOL.get(structure, 'size')[1], farm['size'])
                self.assertEqual(TOOL.get(structure, 'entities')[1], [])
                palette = TOOL.get(structure, 'palette')[1]
                blocks = TOOL.get(structure, 'blocks')[1]
                material, count = expected_plants[template]
                self.assertEqual(sum(TOOL.get(palette[TOOL.get(b, 'state')], 'Name') == material for b in blocks), count)
                self.assertTrue(any('nbt' in b for b in blocks))
                self.assertEqual(hashlib.sha256(data).hexdigest(), provenance['structures'][template]['sha256'])

    def test_comparison_pairs_have_equal_shapes_and_independent_mechanism_output_chunks(self):
        farms = json.loads((ROOT / 'test-worlds/ppl9/farms.json').read_text())['farms']
        by_id = {farm['id']: farm for farm in farms}
        occupied = set()
        outputs = set()
        for farm in farms:
            low, size = farm['destination'], farm['size']
            chunks = {(x, z) for x in range(low[0] // 16, (low[0] + size[0] - 1) // 16 + 1)
                      for z in range(low[2] // 16, (low[2] + size[2] - 1) // 16 + 1)}
            self.assertTrue(occupied.isdisjoint(chunks))
            occupied.update(chunks)
            out = farm['output']
            self.assertNotIn(tuple(out), outputs)
            outputs.add(tuple(out))
            self.assertNotIn((out[0] // 16, out[2] // 16), chunks)
            if 'comparison_of' in farm:
                original = by_id[farm['comparison_of']]
                self.assertEqual(farm['template'], original['template'])
                self.assertEqual(farm['size'], original['size'])
                self.assertEqual(farm['destination'], [original['destination'][0] + 32, *original['destination'][1:]])
                self.assertEqual(farm['output'], [original['output'][0] + 32, *original['output'][1:]])

    def test_codec_roundtrips_complete_real_nbt_payload_without_losing_unknown_tile_fields(self):
        for file in (ROOT / 'test-worlds/ppl9/structures').glob('*.nbt'):
            raw = gzip.decompress(file.read_bytes())
            self.assertEqual(b'\x0a\x00\x00' + TOOL.encode_value(10, TOOL.NBT(raw).root()), raw)

    def test_negative_coordinates_and_non_power_of_two_palette_use_padded_long_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            world = TOOL.World(Path(directory))
            palette = [{'Name': (8, 'minecraft:air')}] * 17
            palette[16] = {'Name': (8, 'minecraft:sugar_cane')}
            # Local x/z=15 for global -1/-1. Palette has 17 entries: 5 bits,
            # twelve entries per long, so index 255 is long 21, offset 3.
            packed = [0] * 22
            packed[21] = 16 << 15
            world.chunks[-1, -1] = {'sections': (9, (10, [{
                'Y': (1, 0), 'block_states': (10, {'palette': (9, (10, palette)), 'data': (12, packed)})
            }]))}
            self.assertEqual(TOOL.get(world.block(-1, 0, -1), 'Name'), 'minecraft:sugar_cane')
            self.assertEqual(TOOL.get(world.block(-2, 0, -1), 'Name'), 'minecraft:air')


if __name__ == '__main__':
    unittest.main()

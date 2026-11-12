"""Validate the compact PPL9-derived zombified-piglin world layout without starting Paper."""
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class MobPerformanceWorldTests(unittest.TestCase):
    def test_default_grid_has_independent_mechanism_and_output_chunks(self):
        fixture = json.loads((ROOT / 'test-worlds/ppl9-mobs-performance/farms.json').read_text(encoding='utf-8'))
        self.assertEqual(fixture['format'], 1)
        self.assertEqual(fixture['world'], 'farm_lab_mob_performance')
        self.assertEqual(fixture['template'], 'skeleton')
        self.assertEqual(fixture['mob'], 'zombified_piglin')
        self.assertEqual(fixture['spawn_count'], 128)
        self.assertEqual(fixture['min_spawn_delay'], 5)
        self.assertEqual(fixture['max_spawn_delay'], 10)
        self.assertEqual(fixture['max_nearby_entities'], 1024)
        self.assertEqual(fixture['default_farms'], 3)
        self.assertGreaterEqual(fixture['maximum_farms'], fixture['default_farms'])
        self.assertEqual(fixture['spacing'], 16)

        columns = 2
        occupied, outputs, mechanism_chunks = set(), set(), set()
        for index in range(fixture['default_farms']):
            x = (index % columns) * fixture['spacing']
            z = (index // columns) * fixture['spacing']
            mechanism = {(block_x, block_z) for block_x in range(x, x + 13)
                         for block_z in range(z, z + 13)}
            output = {(x - 1, z + 5), (x - 1, z + 6)}
            self.assertTrue(occupied.isdisjoint(mechanism))
            self.assertTrue(occupied.isdisjoint(output))
            self.assertTrue(outputs.isdisjoint(mechanism))
            self.assertTrue(outputs.isdisjoint(output))
            occupied |= mechanism
            outputs |= output
            chunks = {(block_x >> 4, block_z >> 4) for block_x, block_z in mechanism}
            self.assertTrue(mechanism_chunks.isdisjoint(chunks))
            mechanism_chunks |= chunks

        # The shared pad is at the centre of the compact 2-by-2 layout, five blocks above
        # the spawners; every spawner centre must be inside vanilla's 16-block activation range.
        afk_x, afk_y, afk_z = 14.5, 89, 14.5
        for index in range(fixture['default_farms']):
            x = (index % columns) * fixture['spacing'] + 6
            z = (index // columns) * fixture['spacing'] + 6
            self.assertLess(((x - afk_x) ** 2 + (84 - afk_y) ** 2 + (z - afk_z) ** 2) ** .5, 16)

    def test_uses_the_checked_in_real_skeleton_structure(self):
        manifest = json.loads((ROOT / 'test-worlds/ppl9-mobs/farms.json').read_text(encoding='utf-8'))
        skeleton = next(farm for farm in manifest['farms'] if farm['id'] == 'skeleton')
        self.assertEqual(skeleton['size'], [13, 25, 13])
        self.assertTrue((ROOT / 'test-worlds/ppl9-mobs/structures/skeleton.nbt').is_file())


if __name__ == '__main__':
    unittest.main()

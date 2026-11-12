"""Validate the high-load PPL9 skeleton-spawner world layout without starting Paper."""
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
        self.assertEqual(fixture['default_farms'], 16)
        self.assertGreaterEqual(fixture['maximum_farms'], fixture['default_farms'])
        self.assertGreaterEqual(fixture['spacing'], 16)

        columns = 4
        occupied, outputs = set(), set()
        for index in range(fixture['default_farms']):
            x = (index % columns) * fixture['spacing']
            z = (index // columns) * fixture['spacing']
            mechanism = {(chunk_x, chunk_z) for chunk_x in range(x // 16, (x + 12) // 16 + 1)
                         for chunk_z in range(z // 16, (z + 12) // 16 + 1)}
            output = ((x - 1) // 16, z // 16)
            self.assertTrue(occupied.isdisjoint(mechanism))
            self.assertNotIn(output, occupied)
            self.assertNotIn(output, outputs)
            occupied |= mechanism
            outputs.add(output)

    def test_uses_the_checked_in_real_skeleton_structure(self):
        manifest = json.loads((ROOT / 'test-worlds/ppl9-mobs/farms.json').read_text(encoding='utf-8'))
        skeleton = next(farm for farm in manifest['farms'] if farm['id'] == 'skeleton')
        self.assertEqual(skeleton['size'], [13, 25, 13])
        self.assertTrue((ROOT / 'test-worlds/ppl9-mobs/structures/skeleton.nbt').is_file())


if __name__ == '__main__':
    unittest.main()

"""Validate the compact PPL9-derived zombified-piglin world layout without starting Paper."""
import json
import os
import subprocess
import tempfile
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

        # The shared pad sits in the gap between the farms' profiling protection belts (mechanism
        # expanded by ProfilingIsolation.MARGIN = 2), five blocks above the spawners; every spawner
        # centre must be inside vanilla's 16-block activation range. The earlier pad at (14, 89, 14)
        # was inside all three belts.
        margin = 2
        afk_block = (fixture['spacing'] - 1, 89, fixture['spacing'] - 1)
        afk_x, afk_y, afk_z = afk_block[0] + .5, afk_block[1], afk_block[2] + .5
        for index in range(fixture['default_farms']):
            x = (index % columns) * fixture['spacing']
            z = (index // columns) * fixture['spacing']
            belt_x = range(x - margin, x + 12 + margin + 1)
            belt_y = range(64 - margin, 88 + margin + 1)
            belt_z = range(z - margin, z + 12 + margin + 1)
            self.assertFalse(afk_block[0] in belt_x and afk_block[1] in belt_y and afk_block[2] in belt_z,
                             f'AFK pad inside the protection belt of farm {index + 1}')
            self.assertLess(((x + 6.5 - afk_x) ** 2 + (84.5 - afk_y) ** 2 + (z + 6.5 - afk_z) ** 2) ** .5, 16)

    def test_uses_the_checked_in_real_skeleton_structure(self):
        manifest = json.loads((ROOT / 'test-worlds/ppl9-mobs/farms.json').read_text(encoding='utf-8'))
        skeleton = next(farm for farm in manifest['farms'] if farm['id'] == 'skeleton')
        self.assertEqual(skeleton['size'], [13, 25, 13])
        self.assertTrue((ROOT / 'test-worlds/ppl9-mobs/structures/skeleton.nbt').is_file())

    def test_benchmark_contract_has_safe_position_reset_and_resolved_inventory_accounting(self):
        source = (ROOT / 'src/farmLab/java/io/github/checkselfy/bakemechanisms/lab/MobPerformanceWorld.java').read_text(encoding='utf-8')
        lab = (ROOT / 'src/farmLab/java/io/github/checkselfy/bakemechanisms/lab/FarmLabPlugin.java').read_text(encoding='utf-8')
        build = (ROOT / 'build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('Safe benchmark AFK/profile position', source)
        self.assertIn('ResolvedOutputInventory.count', lab)
        self.assertIn('profile-rates-per-20-ticks', lab)
        self.assertIn('mob-performance-v', build)
        self.assertIn('BakeFarmLab-worlds.txt', build)


class ResetFarmLabTests(unittest.TestCase):
    """Runs the real Gradle task against a throwaway server directory."""

    @staticmethod
    def _farm(server, name, world):
        farm = server / 'plugins/BakeMechanisms/farms' / name
        farm.mkdir(parents=True)
        (farm / 'farm.yml').write_text(f'format: 2\nid: {name}\nworld: {world}\n', encoding='utf-8')

    @staticmethod
    def _reset(server):
        wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        subprocess.run([str(wrapper), 'resetFarmLab', f'-PfarmLabServer={server}', '-q', '--console=plain'],
                       cwd=ROOT, check=True, capture_output=True, timeout=600)

    def test_reset_drops_registrations_of_every_earlier_lab_world_and_keeps_server_worlds(self):
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary)
            lab = server / 'plugins/BakeFarmLab'
            lab.mkdir(parents=True)
            (lab / 'world-uid.txt').write_text('crop-now\n', encoding='utf-8')
            (lab / 'mob-performance-v7.txt').write_text('world=mobs-now\nfarms=3\n', encoding='utf-8')
            (server / 'plugins/BakeFarmLab-worlds.txt').write_text(
                '# history\nlab=crop-earlier\nlab=crop-now\nserver=overworld\nserver=nether\n', encoding='utf-8')
            for name, world in [('earlier-lab', 'crop-earlier'), ('crop-lab', 'crop-now'), ('mob-lab', 'mobs-now'),
                                ('before-history', 'mobs-generated-before-history'), ('survival', 'overworld'),
                                ('nether', 'nether')]:
                self._farm(server, name, world)
            self._reset(server)
            remaining = sorted(path.name for path in (server / 'plugins/BakeMechanisms/farms').iterdir())
            self.assertEqual(remaining, ['nether', 'survival'])
            self.assertFalse(lab.exists())
            self.assertTrue((server / 'plugins/BakeFarmLab-worlds.txt').is_file(), 'the history survives the reset')

    def test_reset_without_history_keeps_registrations_outside_the_current_lab_worlds(self):
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary)
            lab = server / 'plugins/BakeFarmLab'
            lab.mkdir(parents=True)
            (lab / 'world-uid.txt').write_text('crop-now\n', encoding='utf-8')
            (lab / 'mob-performance-v7.txt').write_text('world=mobs-now\n', encoding='utf-8')
            for name, world in [('crop-lab', 'crop-now'), ('mob-lab', 'mobs-now'), ('survival', 'overworld')]:
                self._farm(server, name, world)
            # Script-prepared lab servers use level-name=lobby: the lab dimensions live there, not in world/.
            (server / 'server.properties').write_text('online-mode=false\nlevel-name=lobby\n', encoding='utf-8')
            lab_dimension = server / 'lobby/dimensions/minecraft/farm_lab_mob_performance'
            lab_dimension.mkdir(parents=True)
            overworld = server / 'lobby/dimensions/minecraft/overworld'
            overworld.mkdir(parents=True)
            self._reset(server)
            remaining = sorted(path.name for path in (server / 'plugins/BakeMechanisms/farms').iterdir())
            self.assertEqual(remaining, ['survival'])
            self.assertFalse(lab_dimension.exists())
            self.assertTrue(overworld.exists())


if __name__ == '__main__':
    unittest.main()

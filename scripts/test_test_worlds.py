"""Safety and portability checks for the fixture archive tooling. Run with unittest."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("test_worlds", Path(__file__).with_name("test-worlds.py"))
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.server = Path(self.temp.name) / "server"
        self.target = Path(self.temp.name) / "worlds.zip"
        level = self.server / "lobby"
        level.mkdir(parents=True)
        (level / "level.dat").write_bytes(b"world-data")
        (level / "session.lock").write_bytes(b"\0" * 8)
        for name in ("bake_blocks", "bake_entities", "bake_performance"):
            world = level / "dimensions" / "minecraft" / name / "region"
            world.mkdir(parents=True)
            (world / "r.0.0.mca").write_bytes(b"region-data")
        manifest = self.server / "plugins" / "BakeTestFixtures" / "manifest.yml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text("fixture-version: 1\n", encoding="utf-8")
        observations = manifest.parent / "observations"
        observations.mkdir()
        (observations / "sample.yml").write_text("requested-ticks: 1200\n", encoding="utf-8")
        (self.server / "paper.jar").write_bytes(b"not-for-redistribution")

    def test_exports_saved_worlds_manifest_and_observations_without_server_or_locks(self):
        TOOL.export(self.server, self.target)
        with zipfile.ZipFile(self.target) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("lobby/level.dat"), b"world-data")
            self.assertEqual(archive.read("lobby/dimensions/minecraft/bake_blocks/region/r.0.0.mca"), b"region-data")
            self.assertIn("plugins/BakeTestFixtures/manifest.yml", archive.namelist())
            self.assertIn("plugins/BakeTestFixtures/observations/sample.yml", archive.namelist())
            self.assertFalse(any(name.endswith((".jar", "session.lock")) for name in archive.namelist()))

    def test_refuses_an_incomplete_world(self):
        (self.server / "lobby" / "dimensions" / "minecraft" / "bake_entities" / "region" / "r.0.0.mca").unlink()
        (self.server / "lobby" / "dimensions" / "minecraft" / "bake_entities" / "region").rmdir()
        with self.assertRaisesRegex(RuntimeError, "Missing saved fixture"):
            TOOL.export(self.server, self.target)
        self.assertFalse(self.target.exists())

    def test_refuses_to_archive_its_own_output(self):
        with self.assertRaisesRegex(RuntimeError, "outside the level"):
            TOOL.export(self.server, self.server / "lobby" / "worlds.zip")

    def test_refuses_a_world_locked_by_another_process(self):
        code = """
import os, sys
handle = open(sys.argv[1], 'r+b')
if os.name == 'nt':
    import msvcrt
    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
else:
    import fcntl
    fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
print('LOCKED', flush=True)
sys.stdin.read(1)
"""
        process = subprocess.Popen(
            [sys.executable, "-c", code, str(self.server / "lobby" / "session.lock")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
        )
        try:
            self.assertEqual(process.stdout.readline().strip(), "LOCKED")
            with self.assertRaises(OSError):
                TOOL.export(self.server, self.target)
            self.assertFalse(self.target.exists())
        finally:
            process.communicate("x", timeout=10)
        # A failed lock acquisition must not leave our own handles locked.
        TOOL.export(self.server, self.target)

    def test_rejects_a_tampered_cached_download_without_network(self):
        jar = self.server / "paper.jar"
        with self.assertRaisesRegex(RuntimeError, "Checksum mismatch"):
            TOOL.download(jar, ("https://example.invalid/unused", "0" * 64))


if __name__ == "__main__":
    unittest.main()

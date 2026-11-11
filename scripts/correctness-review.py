#!/usr/bin/env python3
"""Real-Paper command/lifecycle review in a new disposable directory, including one clean restart.

Set JAVA_HOME (JDK 25). Python uses only its standard library.
Existing EULA acceptance and verified Paper downloads are reused from --cache-server.
"""
import argparse
import importlib.util
import os
import json
import hashlib
import platform
from pathlib import Path
import shutil
import subprocess
import sys
import time

SPEC = importlib.util.spec_from_file_location('test_worlds', Path(__file__).with_name('test-worlds.py'))
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
ROOT = TOOL.ROOT


def validate_log(log, marker):
    if 'CORRECTNESS_FAILED' in log or 'FIXTURES_ERROR' in log:
        raise RuntimeError(f'Failed review before/after {marker}; see fixture-console.log')
    if marker == 'CLIENT_ACCEPTANCE_PASSED' and 'Invalid move player packet' in log:
        raise RuntimeError('Packet client disconnected on invalid movement; input acceptance did not complete')
    if f'REVIEW_PASS {marker}' not in log:
        raise RuntimeError(f'Missing success marker {marker}')


def run(server, cache, short_profile_only=False, client_acceptance_only=False, port=25566, mob_farms_only=False):
    if server.exists():
        raise RuntimeError(f'Refusing existing review directory: {server}; choose a fresh --server')
    if not (cache / 'eula.txt').is_file() or 'eula=true' not in (cache / 'eula.txt').read_text():
        raise RuntimeError('Prepare a fixture server and accept its EULA first; see docs/testing/README.md')
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([str(wrapper), 'build', 'fixturesJar', 'correctnessJar', '--console=plain'], cwd=ROOT, check=True)
    server.mkdir(parents=True)
    for name in ('cache', 'libraries', 'versions'):
        if (cache / name).exists():
            shutil.copytree(cache / name, server / name)
    shutil.copy2(cache / 'paper.jar', server / 'paper.jar')
    shutil.copy2(cache / 'eula.txt', server / 'eula.txt')
    # Verify cached downloads against the same pinned checksums as fixture preparation.
    TOOL.download(server / 'paper.jar', TOOL.PAPER)
    TOOL.download(server / 'cache' / 'mojang_26.3.jar', TOOL.MOJANG)
    (server / 'plugins').mkdir()
    for name in ('bake-mechanisms.jar', 'bake-test-fixtures.jar', 'bake-correctness.jar'):
        shutil.copy2(ROOT / 'build' / 'libs' / name, server / 'plugins' / name)
    if client_acceptance_only:
        (server / 'client-acceptance-only').touch()
    if client_acceptance_only or mob_farms_only:
        (server / 'client-control').mkdir()
    if mob_farms_only:
        (server / 'mob-farms-only').touch()
    if short_profile_only:
        (server / 'short-profile-only').touch()
        config = (ROOT / 'src' / 'main' / 'resources' / 'config.yml').read_text(encoding='utf-8')
        if 'reject-short-useful-segment: false' not in config:
            raise RuntimeError('Default profiling flag changed; update the review runner')
        config = config.replace('reject-short-useful-segment: false', 'reject-short-useful-segment: true')
        folder = server / 'plugins' / 'BakeMechanisms'
        folder.mkdir()
        (folder / 'config.yml').write_text(config, encoding='utf-8')
    provenance = {
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'platform': platform.platform(),
        'java': subprocess.run([TOOL.java(), '-version'], capture_output=True, text=True, check=True).stderr,
        'sha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                   for path in sorted((server / 'plugins').glob('*.jar'))},
        'paper': TOOL.PAPER[1],
        'short_profile_only': short_profile_only,
        'client_acceptance_only': client_acceptance_only,
        'mob_farms_only': mob_farms_only,
        'port': port,
        'note': 'Working-tree changes may exist; plugin hashes identify the actual binaries.',
    }
    (server / 'review-environment.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
    (server / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nwhite-list=false\n'
        'level-name=lobby\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
        'generator-settings={"layers":[{"block":"minecraft:stone","height":64}],"biome":"minecraft:plains"}\n'
        'spawn-protection=0\nview-distance=4\nsimulation-distance=4\n'
        'pause-when-empty-seconds=-1\nmax-tick-time=120000\nallow-flight=true\n', encoding='utf-8')
    markers = ('MOB_FARMS_PASSED',) if mob_farms_only else ('CLIENT_ACCEPTANCE_PASSED',) if client_acceptance_only else (
        ('SHORT_PROFILE_PASSED',) if short_profile_only else ('RESTART_READY', 'OPEN_RESTART_READY', 'CORRECTNESS_PASSED'))
    for marker in markers:
        console = TOOL.Console(server)
        bots = None
        with (server / 'clients.log').open('a', encoding='utf-8') as output:
            try:
                console.wait('Done (')
                client_args = [sys.executable, str(ROOT / 'scripts' / 'correctness-clients.py'), '--port', str(port)]
                if client_acceptance_only or mob_farms_only:
                    client_args += ['--control-dir', str(server / 'client-control')]
                bots = subprocess.Popen(client_args,
                                        cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
                # Keep the raw log and fail immediately on review assertions.
                while True:
                    line = console.wait('REVIEW_', timeout=600)
                    if 'CORRECTNESS_FAILED' in line:
                        raise RuntimeError('Review assertion failed; see fixture-console.log')
                    if marker in line:
                        break
                console.process.wait(timeout=120)
            finally:
                if bots is not None:
                    bots.terminate()
                    bots.wait(timeout=15)
                console.stop()
        log = (server / 'logs' / 'latest.log').read_text(encoding='utf-8')
        shutil.copy2(server / 'logs' / 'latest.log', server / f'{marker.lower()}.log')
        validate_log(log, marker)
        if marker == 'OPEN_RESTART_READY':
            start = time.monotonic()
            time.sleep(3)
            (server / 'offline-interval.json').write_text(json.dumps({
                'minimum_offline_seconds': time.monotonic() - start,
                'note': 'Measured after clean process exit and before starting the next JVM; startup adds more offline time.',
            }, indent=2), encoding='utf-8')
    print(f'CORRECTNESS_REVIEW_PASSED: {server}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, default=ROOT / 'run' / 'correctness-review')
    parser.add_argument('--cache-server', type=Path, default=ROOT / 'run' / 'fixture-lab')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--short-profile-only', action='store_true',
                        help='Only check zero output and saturated short-segment rejection with the flag enabled.')
    mode.add_argument('--client-acceptance-only', action='store_true', help='Exercise real packet input and received messages.')
    mode.add_argument('--mob-farms-only', action='store_true', help='Review imported PPL9 skeleton/zombie farms with native armor drops.')
    parser.add_argument('--port', type=int, default=25566)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('port must be between 1 and 65535')
    run(args.server.resolve(), args.cache_server.resolve(), args.short_profile_only, args.client_acceptance_only, args.port, args.mob_farms_only)

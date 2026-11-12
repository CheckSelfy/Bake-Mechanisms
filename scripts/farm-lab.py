#!/usr/bin/env python3
"""Build, prepare and run the shared extracted PPL9 farm world on local Paper."""
import argparse
import hashlib
import importlib.util
import json
import os
import queue
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('test_worlds', Path(__file__).with_name('test-worlds.py'))
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


def configure_java():
    if not os.environ.get('JAVA_HOME') and not shutil.which('java'):
        candidates = sorted((ROOT / '.gradle' / 'tooling').glob('jdk-25*'))
        if candidates:
            os.environ['JAVA_HOME'] = str(candidates[-1])
    return TOOL.java()


def prepare(server, cache, headless=False, accept_eula=False, port=25566, review_jar='bake-farm-lab-review.jar'):
    configure_java()
    marker = server / 'farm-lab-server.json'
    if server.exists():
        if headless or not marker.exists():
            raise RuntimeError(f'Refusing existing directory: {server}; use a fresh --server')
        if json.loads(marker.read_text())['headless']:
            raise RuntimeError('Choose a separate manual copy; this server belongs to a destructive headless run')
        # Java/Paper owns this exclusive lock while running. Check before touching plugin JARs.
        lock = server / 'lobby' / 'session.lock'
        if lock.exists():
            with lock.open('r+b') as handle:
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    accepted = cache / 'eula.txt'
    if not accept_eula and not (marker.exists() or (accepted.exists() and 'eula=true' in accepted.read_text())):
        raise RuntimeError('Accept Minecraft EULA first: pass --accept-eula or prepare an accepted local fixture cache')
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([str(wrapper), 'jar', 'farmLabJar', 'farmLabReviewJar', 'mobPerformanceReviewJar', 'slimeGalleryJar',
                    '--console=plain'], cwd=ROOT, check=True)
    server.mkdir(parents=True, exist_ok=True)
    if not marker.exists():
        for name in ('cache', 'libraries', 'versions'):
            if (cache / name).exists():
                shutil.copytree(cache / name, server / name)
        if (cache / 'paper.jar').exists():
            shutil.copy2(cache / 'paper.jar', server / 'paper.jar')
        TOOL.download(server / 'paper.jar', TOOL.PAPER)
        TOOL.download(server / 'cache' / 'mojang_26.3.jar', TOOL.MOJANG)
        (server / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
        (server / 'plugins').mkdir()
        (server / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n'
            'white-list=false\nlevel-name=lobby\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
            'generator-settings={"layers":[{"block":"minecraft:stone","height":64}],"biome":"minecraft:plains"}\n'
            'spawn-protection=0\nview-distance=5\nsimulation-distance=5\n'
            'pause-when-empty-seconds=-1\nmax-tick-time=120000\nallow-flight=true\n', encoding='utf-8')
        marker.write_text(json.dumps({'format': 1, 'headless': headless, 'port': port}, indent=2), encoding='utf-8')
    names = ['bake-mechanisms.jar', 'bake-farm-lab.jar', 'bake-slime-gallery.jar']
    if headless:
        names.append(review_jar)
    for name in names:
        shutil.copy2(ROOT / 'build' / 'libs' / name, server / 'plugins' / name)
    (server / 'lab-environment.json').write_text(json.dumps({
        'plugins_sha256': {name: hashlib.sha256((server / 'plugins' / name).read_bytes()).hexdigest() for name in names},
        'source': 'test-worlds/ppl9/farms.json and structures/*.nbt',
        'paper_sha256': TOOL.PAPER[1],
    }, indent=2), encoding='utf-8')
    actual_port = json.loads(marker.read_text()).get('port', 25566)
    print(f'Farm lab prepared in {server}. Connect Minecraft 26.3 to 127.0.0.1:{actual_port}.', flush=True)


def check_or_review(server, headless, port=25566):
    console = TOOL.Console(server)
    bots = None
    try:
        console.wait('Done (')
        log = (server / 'logs' / 'latest.log').read_text(encoding='utf-8')
        expected = len(json.loads((ROOT / 'test-worlds/ppl9/farms.json').read_text())['farms'])
        if f'FARM_LAB_READY farms={expected}' not in log or 'Error occurred while enabling' in log:
            raise RuntimeError('Farm lab did not initialize; see logs/latest.log')
        if 'FARM_LAB_MOBS_READY farms=2' not in log:
            raise RuntimeError('Manual mob plots did not initialize; see logs/latest.log')
        if headless:
            with (server / 'clients.log').open('w', encoding='utf-8') as output:
                bots = subprocess.Popen([sys.executable, str(ROOT / 'scripts' / 'correctness-clients.py'), '--port', str(port)],
                                        cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 600
                while True:
                    if time.monotonic() > deadline:
                        raise RuntimeError('Headless lab timed out')
                    try:
                        line = console.lines.get(timeout=1)
                    except queue.Empty:
                        if console.process.poll() is not None:
                            raise RuntimeError('Server stopped before completing the review')
                        continue
                    if 'FARM_LAB_REVIEW_' in line:
                        print(line, flush=True)
                    if 'FARM_LAB_REVIEW_FAILED' in line:
                        raise RuntimeError('Imported farm review failed; see logs/latest.log')
                    if 'FARM_LAB_REVIEW_PASSED' in line:
                        break
                    if console.process.poll() is not None:
                        raise RuntimeError('Server stopped before completing the review')
                console.process.wait(timeout=120)
        else:
            console.send('farmlab list')
            console.send('farmlab check-mobs')
            console.wait('FARM_LAB_MOBS_CHECK_OK')
    finally:
        if bots is not None:
            bots.terminate()
            bots.wait(timeout=15)
        console.stop()
    print('FARM_LAB_HEADLESS_PASSED' if headless else 'FARM_LAB_CHECK_PASSED', flush=True)


def mob_review(server, mode, port=25566, evidence=None):
    """#12 headless review of the three-farm zombified-piglin benchmark around a real resetFarmLab.

    First process: the non-OP client registers the three farms (MOB_REVIEW_SEEDED) and the server stops.
    Then gradlew resetFarmLab deletes the lab worlds and their registrations. Second process: the lab is
    regenerated, nothing stale survives, all three farms register again; smoke mode profiles them in parallel
    from the safe AFK pad and enters stasis automatically; acceptance mode also benchmarks the original and
    stasis phases from the same position, checks production over exactly 1,200 game ticks and restores.
    """
    configure_java()
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    (server / 'mob-review-mode.txt').write_text(mode + '\n', encoding='utf-8')

    def run_process(marker, timeout):
        console = TOOL.Console(server)
        bots = None
        try:
            console.wait('Done (', timeout=600)
            with (server / 'clients.log').open('a', encoding='utf-8') as output:
                bots = subprocess.Popen([sys.executable, str(ROOT / 'scripts' / 'correctness-clients.py'), '--port', str(port)],
                                        cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + timeout
                while True:
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Mob review timed out waiting for {marker}')
                    try:
                        line = console.lines.get(timeout=1)
                    except queue.Empty:
                        if console.process.poll() is not None:
                            raise RuntimeError(f'Server stopped before {marker}; see logs/latest.log')
                        continue
                    if 'MOB_REVIEW_' in line or 'FARM_LAB_MOB_PERFORMANCE_READY' in line:
                        print(line, flush=True)
                    if 'MOB_REVIEW_FAILED' in line:
                        raise RuntimeError('Mob performance review failed; see logs/latest.log')
                    if marker in line:
                        break
                console.process.wait(timeout=300)
        finally:
            if bots is not None:
                bots.terminate()
                bots.wait(timeout=15)
            console.stop()

    run_process('MOB_REVIEW_SEEDED', 1200)
    subprocess.run([str(wrapper), 'resetFarmLab', f'-PfarmLabServer={server}', '--console=plain'], cwd=ROOT, check=True)
    run_process('MOB_REVIEW_PASSED', 7200)
    if evidence is not None:
        evidence.mkdir(parents=True, exist_ok=True)
        review = server / 'plugins' / 'BakeMobPerformanceReview'
        for name in ('acceptance-result.yml', 'seeded.yml'):
            if (review / name).exists():
                shutil.copy2(review / name, evidence / name)
        # MobBenchmark records under the data folder of the plugin that started it.
        for benchmarks in (server / 'plugins' / 'BakeFarmLab' / 'benchmarks', review / 'benchmarks'):
            for record in sorted(benchmarks.glob('*.yml')) if benchmarks.exists() else []:
                shutil.copy2(record, evidence / record.name)
        log = (server / 'logs' / 'latest.log').read_text(encoding='utf-8', errors='replace')
        (evidence / 'review.log').write_text('\n'.join(line for line in log.splitlines()
                                                     if 'MOB_REVIEW_' in line or 'spark' in line.lower()) + '\n', encoding='utf-8')
        spark = server / 'plugins' / 'spark'
        artifacts = []
        for path in sorted(spark.rglob('*')) if spark.exists() else []:
            if path.is_file() and path.suffix == '.sparkprofile':
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                artifacts.append(f'{path.relative_to(server).as_posix()}\t{path.stat().st_size}\tsha256={digest}')
                if path.stat().st_size <= 4 * 1024 * 1024:
                    shutil.copy2(path, evidence / path.name)
        (evidence / 'spark-artifacts.txt').write_text('\n'.join(artifacts) + '\n', encoding='utf-8')
        print(f'Evidence copied to {evidence}', flush=True)
    print('MOB_REVIEW_HEADLESS_PASSED', mode, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'run', 'check', 'headless', 'mob-review'))
    parser.add_argument('--mode', choices=('smoke', 'acceptance'), default='smoke',
                        help='mob-review: smoke registers/profiles/stasises; acceptance adds the exact 1,200-tick and TPS checks')
    parser.add_argument('--evidence', type=Path, help='mob-review: copy result records and Spark artifacts here')
    parser.add_argument('--server', type=Path)
    parser.add_argument('--cache-server', type=Path, default=ROOT / 'run' / 'fixture-lab')
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--port', type=int, default=25566)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('port must be between 1 and 65535')
    if args.action == 'mob-review':
        server = (args.server or ROOT / 'run' / f'farm-lab-mob-{args.mode}').resolve()
        prepare(server, args.cache_server.resolve(), True, args.accept_eula, args.port, 'bake-mob-performance-review.jar')
        actual_port = json.loads((server / 'farm-lab-server.json').read_text()).get('port', 25566)
        mob_review(server, args.mode, actual_port, args.evidence.resolve() if args.evidence else None)
        return
    headless = args.action == 'headless'
    server = (args.server or ROOT / 'run' / ('farm-lab-headless' if headless else 'farm-lab')).resolve()
    prepare(server, args.cache_server.resolve(), headless, args.accept_eula, args.port)
    if args.action == 'run':
        subprocess.run([configure_java(), '-Dterminal.jline=false', '-Dterminal.ansi=false',
                        '-Xms512M', '-Xmx2G', '-jar', 'paper.jar', 'nogui'], cwd=server, check=True)
    elif args.action in ('check', 'headless'):
        actual_port = json.loads((server / 'farm-lab-server.json').read_text()).get('port', 25566)
        check_or_review(server, headless, actual_port)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))

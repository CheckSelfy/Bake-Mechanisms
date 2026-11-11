#!/usr/bin/env python3
"""Single entry point for the real-Paper end-to-end suite (issue #27).

Builds every JAR once, then runs each suite against a fresh, disposable Paper 26.3 directory
under run/e2e/<timestamp>/ on a free localhost port. Every server is stopped on success,
failure, timeout and Ctrl+C. The result is summary.md / summary.json in the run directory
and a non-zero exit code if any suite failed.

Requires JDK 25 (JAVA_HOME or --java-home) and Python 3.11+; standard library only.
"""
import argparse
import dataclasses
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time

if sys.version_info < (3, 11):
    sys.exit(f'Python 3.11+ is required (found {platform.python_version()}); on Windows try: py -3.13 scripts/e2e.py')

# Echoed server lines may contain characters the console code page (e.g. cp1251) cannot encode;
# that must never abort a run. Files are always written as UTF-8.
for _stream in (sys.stdout, sys.stderr):
    _stream.reconfigure(errors='replace')

SPEC = importlib.util.spec_from_file_location('test_worlds', Path(__file__).with_name('test-worlds.py'))
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
ROOT = TOOL.ROOT
LIBS = ROOT / 'build' / 'libs'

# Lines that mean the run cannot be trusted regardless of which suite is running.
GENERIC_FAILURES = (
    'Error occurred while enabling',
    'Could not load plugin',
    "Could not load '",
    'Exception in server tick loop',
    'Encountered an unexpected exception',
)

SMOKE_PROPERTIES = ('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n'
                    'view-distance=2\nsimulation-distance=2\n')
SMOKE_SPIGOT = '''world-settings:
  default:
    entity-activation-range:
      animals: 0
      monsters: 0
      villagers: 0
      raiders: 0
      water: 0
      flying-monsters: 0
      misc: 0
'''
REVIEW_PROPERTIES = (
    'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nwhite-list=false\n'
    'level-name=lobby\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
    'generator-settings={{"layers":[{{"block":"minecraft:stone","height":64}}],"biome":"minecraft:plains"}}\n'
    'spawn-protection=0\nview-distance=4\nsimulation-distance=4\n'
    'pause-when-empty-seconds=-1\nmax-tick-time=120000\nallow-flight=true\n')
SMOKE_JVM = ('-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms512M', '-Xmx1G')
REVIEW_JVM = ('-Dterminal.jline=false', '-Dterminal.ansi=false', '-Djava.net.preferIPv4Stack=true',
              '-Xms512M', '-Xmx2G')


@dataclasses.dataclass(frozen=True)
class Phase:
    """One Paper process. The test plugin shuts the server down itself when it is done."""
    name: str
    expected: tuple[str, ...]          # markers that must all appear in the log
    failures: tuple[str, ...]          # any of these fails the phase immediately
    progress: str                      # substring that resets the idle watchdog
    jvm: tuple[str, ...] = SMOKE_JVM
    clients: bool = False
    idle_timeout: int = 600            # wall seconds without a progress line (watchdog only)
    total_timeout: int = 3600


@dataclasses.dataclass(frozen=True)
class Suite:
    name: str
    plugins: tuple[str, ...]
    phases: tuple[Phase, ...]
    network: bool = False              # needs a real port and the headless clients
    short_profile: bool = False


def review_phase(name, ids):
    return Phase(name, tuple(f'REVIEW_PASS {i}' for i in ids), ('REVIEW_CORRECTNESS_FAILED',), 'REVIEW_',
                 jvm=REVIEW_JVM, clients=True)


SMOKE_FAIL = ('BLOCK_STASIS_SMOKE_FAILED',)
SUITES = (
    Suite('stasis-smoke', ('bake-mechanisms.jar', 'bake-stasis-smoke.jar'), (
        Phase('stasis-smoke', (
            'BLOCK_STASIS_SMOKE_PASSED', 'ENTITY_STASIS_SMOKE_PASSED', 'CHICKEN_STASIS_SMOKE_PASSED',
            'SNAPSHOT_SAVE_FAILURE_SMOKE_PASSED', 'FRAGILE_BLOCK_STASIS_SMOKE_PASSED', 'SIGN_STASIS_SMOKE_PASSED',
            'EXTENDED_BLOCK_STASIS_SMOKE_PASSED', 'MOVING_PISTON_STASIS_SMOKE_PASSED', 'MOB_BEHAVIOR_SMOKE_PASSED',
            'PROFILING_INPUT_SMOKE_PASSED', 'PROFILING_NATURAL_INPUT_SMOKE_PASSED',
        ), SMOKE_FAIL, '_SMOKE_'),
    )),
    # Two JVMs with a clean shutdown between them: no in-memory entity can reach restore.
    Suite('entity-nbt', ('bake-mechanisms.jar', 'bake-stasis-smoke.jar'), (
        Phase('capture', ('ENTITY_NBT_CAPTURE_PASSED',), SMOKE_FAIL, 'ENTITY_NBT_',
              jvm=SMOKE_JVM + ('-Dbake.entity-nbt.phase=capture',)),
        Phase('restore', ('ENTITY_NBT_RESTART_PASSED',), SMOKE_FAIL, 'ENTITY_NBT_',
              jvm=SMOKE_JVM + ('-Dbake.entity-nbt.phase=restore',)),
    )),
    Suite('correctness', ('bake-mechanisms.jar', 'bake-test-fixtures.jar', 'bake-correctness.jar'), (
        review_phase('restart-ready', ('C04_EMPTY_SATURATED', 'C02_WARMUP', 'C02', 'C01_REGISTER', 'C01_RIGHTS',
                                       'C04_CANCEL', 'C03', 'C05_OCCUPIED', 'C05', 'C06_C07', 'C09_ENTITIES',
                                       'C08_PAUSE', 'RESTART_READY')),
        review_phase('correctness-passed', ('C10', 'C08_RESUME', 'C09_BLOCKS', 'C11', 'CORRECTNESS_PASSED')),
    ), network=True),
    Suite('short-profile', ('bake-mechanisms.jar', 'bake-test-fixtures.jar', 'bake-correctness.jar'), (
        review_phase('short-profile', ('SHORT_PROFILE_PASSED',)),
    ), network=True, short_profile=True),
)


# Acceptance criteria of issue #27 -> markers that confirm them in this run, plus what is still not covered.
# Criteria about the runner itself (one command, isolation, tick-based waits, diagnostics) have no markers.
R = 'REVIEW_PASS '
COVERAGE = (
    ('Полный цикл: разметка → профилирование → READY → стазис → эмуляция → восстановление → NEEDS_PROFILING',
     (R + 'C01_REGISTER', R + 'C03', R + 'C05', R + 'C06_C07', R + 'C09_ENTITIES', R + 'C09_BLOCKS'), None),
    ('Во время стазиса оригинала нет, продукция только от эмуляции',
     (R + 'C05', R + 'C06_C07'), 'нет отдельной проверки, что выход растёт только на величину эмуляции'),
    ('Производство против профиля за известное число тиков, с дробями',
     (R + 'C06_C07', R + 'C08_RESUME'), None),
    ('Переполнение, приостановка, возобновление без потерь и дублирования',
     (R + 'C08_PAUSE', R + 'C08_RESUME'), 'дублирование не проверяется отдельно по сумме «выход + буфер»'),
    ('Восстановление блоков, контейнеров, сущностей с NBT и пассажирами',
     (R + 'C09_BLOCKS', R + 'C09_ENTITIES', 'ENTITY_STASIS_SMOKE_PASSED', 'CHICKEN_STASIS_SMOKE_PASSED',
      'SIGN_STASIS_SMOKE_PASSED', 'EXTENDED_BLOCK_STASIS_SMOKE_PASSED', 'MOVING_PISTON_STASIS_SMOKE_PASSED',
      'ENTITY_NBT_RESTART_PASSED'), 'NBT и пассажиры проверены в smoke-наборах, не в полном цикле с командами'),
    ('Рестарт в стазисе сохраняет снимок/буфер/дроби; рестарт в профилировании отменяет замер',
     (R + 'RESTART_READY', R + 'C10', 'ENTITY_NBT_RESTART_PASSED'),
     'рестарт при свободном выходе (отсутствие offline-начисления) не проверяется'),
    ('Повторное профилирование после восстановления и безопасное удаление',
     (R + 'C11',), None),
    ('Права создателя и отказ при неподдерживаемом содержимом до изменения мира',
     (R + 'C01_RIGHTS', R + 'C05_OCCUPIED', 'BLOCK_STASIS_SMOKE_PASSED', 'SNAPSHOT_SAVE_FAILURE_SMOKE_PASSED',
      'ENTITY_NBT_CAPTURE_PASSED'), 'отказ при неподдерживаемом содержимом проверен через сервис, не командой игрока'),
)


def marker_regex(marker):
    # Whole-token match: BLOCK_STASIS_SMOKE_PASSED must not be satisfied by FRAGILE_BLOCK_STASIS_SMOKE_PASSED.
    return re.compile(r'(?<![A-Za-z0-9_])' + re.escape(marker) + r'(?![A-Za-z0-9_])')


def evaluate(lines, phase, returncode, watchdog=None):
    """Pure verdict for one phase from its console lines; separated for unit tests."""
    failure = None
    for index, line in enumerate(lines):
        if any(f in line for f in phase.failures + GENERIC_FAILURES):
            # Keep the stack trace that follows the marker; that is usually the actual cause.
            failure = '\n'.join(lines[index:index + 20])
            break
    text = '\n'.join(lines)
    passed = [m for m in phase.expected if marker_regex(m).search(text)]
    missing = [m for m in phase.expected if m not in passed]
    if failure:
        reason = failure
    elif watchdog:
        reason = watchdog
    elif missing:
        reason = 'missing markers: ' + ', '.join(missing)
    elif returncode != 0:
        reason = f'server exit code {returncode}'
    else:
        reason = None
    return {'phase': phase.name, 'status': 'FAIL' if reason else 'PASS', 'reason': reason,
            'passed': passed, 'missing': missing, 'exit_code': returncode}


def java_home(argument):
    home = Path(argument or os.environ.get('JAVA_HOME', ''))
    executable = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    if not executable.is_file():
        raise RuntimeError('JDK 25 is required: set JAVA_HOME or pass --java-home')
    version = subprocess.run([str(executable), '-version'], capture_output=True, text=True).stderr
    if not re.search(r'version "25[".]', version):
        raise RuntimeError(f'JDK 25 is required, {executable} reports:\n{version.strip()}')
    return home, executable, version.strip()


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare_cache(cache, accept_eula):
    eula = cache / 'eula.txt'
    if accept_eula:
        cache.mkdir(parents=True, exist_ok=True)
        eula.write_text('eula=true\n', encoding='utf-8')
    elif not eula.is_file() or 'eula=true' not in eula.read_text(encoding='utf-8'):
        raise RuntimeError('Pass --accept-eula after accepting https://aka.ms/MinecraftEULA for these local test servers')
    # Downloads are verified against the same pinned SHA-256 as the fixture tooling.
    TOOL.download(cache / 'paper.jar', TOOL.PAPER)
    TOOL.download(cache / 'cache' / 'mojang_26.3.jar', TOOL.MOJANG)


def build(java):
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    environment = dict(os.environ, JAVA_HOME=str(java))
    subprocess.run([str(wrapper), 'build', 'fixturesJar', 'correctnessJar', 'stasisSmokeJar', '--console=plain'],
                   cwd=ROOT, env=environment, check=True)


def create_server(directory, suite, cache, port):
    directory.mkdir(parents=True)
    # Paper's patched server and libraries are reused from earlier runs to save the extraction.
    for name in ('libraries', 'versions'):
        if (cache / name).is_dir():
            shutil.copytree(cache / name, directory / name)
    (directory / 'cache').mkdir(exist_ok=True)
    shutil.copy2(cache / 'paper.jar', directory / 'paper.jar')
    shutil.copy2(cache / 'cache' / 'mojang_26.3.jar', directory / 'cache' / 'mojang_26.3.jar')
    shutil.copy2(cache / 'eula.txt', directory / 'eula.txt')
    (directory / 'plugins').mkdir()
    for name in suite.plugins:
        shutil.copy2(LIBS / name, directory / 'plugins' / name)
    if suite.network:
        (directory / 'server.properties').write_text(REVIEW_PROPERTIES.format(port=port), encoding='utf-8')
    else:
        (directory / 'server.properties').write_text(SMOKE_PROPERTIES, encoding='utf-8')
        (directory / 'spigot.yml').write_text(SMOKE_SPIGOT, encoding='utf-8')
    if suite.short_profile:
        (directory / 'short-profile-only').touch()
        config = (ROOT / 'src' / 'main' / 'resources' / 'config.yml').read_text(encoding='utf-8')
        if 'reject-short-useful-segment: false' not in config:
            raise RuntimeError('Default profiling flag changed; update scripts/e2e.py')
        folder = directory / 'plugins' / 'BakeMechanisms'
        folder.mkdir()
        (folder / 'config.yml').write_text(
            config.replace('reject-short-useful-segment: false', 'reject-short-useful-segment: true'), encoding='utf-8')


def seed_cache(cache, directory):
    for name in ('libraries', 'versions'):
        if (directory / name).is_dir() and not (cache / name).exists():
            shutil.copytree(directory / name, cache / name)


def isolated():
    # Own process group: Ctrl+C reaches only this script, which then stops the server cleanly.
    if os.name == 'nt':
        return {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP}
    return {'start_new_session': True}


def stop(process, grace=120):
    if process is None:
        return
    if process.poll() is None:
        try:
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.wait(timeout=grace)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            process.kill()
            process.wait(timeout=30)
    # Close explicitly: on Windows, flushing the pipe of an exited server during garbage collection raises EINVAL.
    try:
        process.stdin.close()
    except OSError:
        pass


def run_phase(directory, phase, java, port):
    """Runs one Paper process to completion and returns its verdict. Never leaves a process behind."""
    lines, inbox = [], queue.Queue()
    console_log = directory / f'{phase.name}.console.log'
    server = clients = None
    watchdog = None
    started = time.monotonic()
    try:
        server = subprocess.Popen([str(java), *phase.jvm, '-jar', 'paper.jar', 'nogui'], cwd=directory,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding='utf-8', errors='replace', **isolated())

        def pump():
            with console_log.open('w', encoding='utf-8') as output:
                for raw in server.stdout:
                    output.write(raw)
                    output.flush()
                    inbox.put(raw.rstrip('\n'))
            inbox.put(None)

        threading.Thread(target=pump, daemon=True).start()
        last_progress = time.monotonic()
        failed_at = None
        while True:
            try:
                line = inbox.get(timeout=1)
            except queue.Empty:
                line = ''
            if line is None:
                break
            now = time.monotonic()
            if line:
                lines.append(line)
                if phase.progress in line or 'Done (' in line:
                    last_progress = now
                    print(f'  [{phase.name}] {line.strip()[-160:]}', flush=True)
                if phase.clients and clients is None and 'Done (' in line:
                    clients_log = (directory / 'clients.log').open('a', encoding='utf-8')
                    clients = subprocess.Popen([sys.executable, str(ROOT / 'scripts' / 'correctness-clients.py'),
                                                '--port', str(port)], cwd=ROOT, stdout=clients_log,
                                               stderr=subprocess.STDOUT, **isolated())
                if failed_at is None and any(f in line for f in phase.failures + GENERIC_FAILURES):
                    failed_at = now
            # The test plugin shuts down after a failure; give it a bounded chance to do so.
            if failed_at is not None and now - failed_at > 120:
                break
            if now - last_progress > phase.idle_timeout:
                watchdog = f'watchdog: no progress line for {phase.idle_timeout}s'
                break
            if now - started > phase.total_timeout:
                watchdog = f'watchdog: phase exceeded {phase.total_timeout}s'
                break
    finally:
        if clients is not None:
            clients.terminate()
            try:
                clients.wait(timeout=15)
            except subprocess.TimeoutExpired:
                clients.kill()
        stop(server)
    latest = directory / 'logs' / 'latest.log'
    if latest.is_file():
        shutil.copy2(latest, directory / f'{phase.name}.log')
    verdict = evaluate(lines, phase, server.returncode, watchdog)
    verdict['seconds'] = round(time.monotonic() - started)
    verdict['log'] = shown(directory / f'{phase.name}.log') if latest.is_file() else None
    verdict['console'] = shown(console_log)
    verdict['tail'] = lines[-30:] if verdict['status'] == 'FAIL' else []
    return verdict


def shown(path):
    return str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def coverage(report):
    """Per criterion: how many of its markers this run confirmed. Unselected suites count as not run."""
    seen = {m for suite in report['suites'] for phase in suite['phases'] for m in phase['passed']}
    for name, markers, gap in COVERAGE:
        confirmed = sum(m in seen for m in markers)
        status = 'PASS' if confirmed == len(markers) else f'{confirmed}/{len(markers)}'
        yield name, status, gap


def write_summary(output, report):
    (output / 'summary.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    rows = ['# E2E run', '', f"Result: **{report['result']}**", '',
            f"- Commit: `{report['environment']['commit']}`" + (' (dirty working tree)' if report['environment']['dirty'] else ''),
            f"- Paper: `{report['environment']['paper']}`", f"- Java: `{report['environment']['java'].splitlines()[0]}`",
            f"- Platform: `{report['environment']['platform']}`", '',
            '| Suite | Phase | Status | Seconds | Markers |', '|---|---|---|---:|---|']
    for suite in report['suites']:
        for phase in suite['phases']:
            rows.append(f"| {suite['name']} | {phase['phase']} | {phase['status']} | {phase.get('seconds', '')} | "
                        f"{len(phase['passed'])}/{len(phase['passed']) + len(phase['missing'])} |")
        if suite.get('skipped'):
            rows.append(f"| {suite['name']} | — | SKIPPED | | {suite['skipped']} |")
    for suite in report['suites']:
        for phase in suite['phases']:
            if phase['status'] == 'FAIL':
                rows += ['', f"## {suite['name']} / {phase['phase']}: FAIL", '', '```text', phase['reason'], '```',
                         '', f"Log: `{phase['log']}`, console: `{phase['console']}`", '', 'Console tail:', '',
                         '```text', *phase['tail'], '```']
    rows += ['', '## Критерии #27', '', '| Критерий | Маркеры | Пробел |', '|---|---|---|']
    for name, status, gap in coverage(report):
        rows.append(f'| {name} | {status} | {gap or "—"} |')
    rows += ['', '## Binaries', '', *[f'- `{name}`: `{digest}`' for name, digest in report['sha256'].items()]]
    (output / 'summary.md').write_text('\n'.join(rows) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--accept-eula', action='store_true',
                        help='Accept https://aka.ms/MinecraftEULA for the disposable local servers (stored in --cache).')
    parser.add_argument('--java-home', help='JDK 25 directory (defaults to JAVA_HOME).')
    parser.add_argument('--suite', action='append', choices=[s.name for s in SUITES],
                        help='Run only this suite; repeatable. Default: all, in order.')
    parser.add_argument('--output', type=Path, help='Fresh run directory (default run/e2e/<timestamp>).')
    parser.add_argument('--cache', type=Path, default=ROOT / 'run' / 'e2e-cache',
                        help='Verified Paper/Mojang downloads, EULA acceptance and extracted libraries.')
    parser.add_argument('--skip-build', action='store_true', help='Reuse the JARs already in build/libs.')
    parser.add_argument('--fail-fast', action='store_true', help='Stop after the first failed suite.')
    args = parser.parse_args()

    home, java, java_version = java_home(args.java_home)
    output = (args.output or ROOT / 'run' / 'e2e' / datetime.datetime.now().strftime('%Y%m%d-%H%M%S')).resolve()
    if output.exists():
        raise RuntimeError(f'Refusing existing run directory {output}; choose a fresh --output')
    cache = args.cache.resolve()
    prepare_cache(cache, args.accept_eula)
    if not args.skip_build:
        build(home)
    output.mkdir(parents=True)
    selected = [s for s in SUITES if not args.suite or s.name in args.suite]
    jars = sorted({name for suite in selected for name in suite.plugins})
    report = {
        'result': 'RUNNING', 'started': datetime.datetime.now().isoformat(timespec='seconds'),
        'environment': {'commit': git('rev-parse', 'HEAD'), 'dirty': bool(git('status', '--porcelain')),
                        'platform': platform.platform(), 'java': java_version, 'paper': TOOL.PAPER[1],
                        'python': platform.python_version()},
        'sha256': {name: sha256(LIBS / name) for name in jars},
        'suites': [],
    }
    failed = False
    try:
        for suite in selected:
            entry = {'name': suite.name, 'phases': []}
            report['suites'].append(entry)
            if failed and args.fail_fast:
                entry['skipped'] = 'fail-fast'
                continue
            directory = output / suite.name
            port = free_port() if suite.network else 0
            print(f'== {suite.name} ({shown(directory)}' + (f', port {port}' if port else '') + ')', flush=True)
            create_server(directory, suite, cache, port)
            for phase in suite.phases:
                verdict = run_phase(directory, phase, java, port)
                entry['phases'].append(verdict)
                seed_cache(cache, directory)
                print(f"   {phase.name}: {verdict['status']}" + (f" — {verdict['reason'].splitlines()[0]}"
                                                                if verdict['reason'] else ''), flush=True)
                if verdict['status'] == 'FAIL':
                    failed = True
                    break  # later phases of the same suite depend on this one
            write_summary(output, report)
    except KeyboardInterrupt:
        failed = True
        report['interrupted'] = True
        print('Interrupted; servers stopped.', flush=True)
    report['result'] = 'FAIL' if failed else 'PASS'
    report['finished'] = datetime.datetime.now().isoformat(timespec='seconds')
    write_summary(output, report)
    print(f"E2E_{report['result']}: {output / 'summary.md'}", flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(f'E2E_ERROR: {error}')

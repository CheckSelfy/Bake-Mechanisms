#!/usr/bin/env python3
"""Check creation, player commands and clean-restart reuse of the disposable Minecraft slime gallery."""
import argparse
import importlib.util
from pathlib import Path
import threading

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def run(server, cache, port):
    if server.exists():
        raise RuntimeError(f'Use a fresh test directory: {server}')
    lab = module('slime_farm_lab', ROOT / 'scripts/farm-lab.py')
    clients = module('slime_clients', ROOT / 'scripts/correctness-clients.py')
    lab.prepare(server, cache, port=port)
    marker = server / 'plugins/BakeSlimeGallery/slime-gallery-v1.txt'
    original = None
    for index in (1, 9):
        console = lab.TOOL.Console(server)
        bot = None
        try:
            console.wait('Done (')

            def connect():
                try:
                    clients.connect('SlimeReview', command=f'slimegallery {index}', port=port)
                except (EOFError, OSError):
                    pass  # Expected when the test server is stopped below.

            bot = threading.Thread(target=connect, daemon=True)
            bot.start()
            if index == 1:
                console.wait('SLIME_GALLERY_CREATED sizes=9', timeout=120)
            console.wait(f'SLIME_GALLERY_VISIT index={index}', timeout=120)
            if index == 1:
                original = marker.read_bytes()
            elif marker.read_bytes() != original:
                raise AssertionError('Restart rebuilt or replaced the existing gallery')
            print(f'SLIME_GALLERY_REVIEW_PASS index={index}', flush=True)
        finally:
            console.stop()
            if bot is not None:
                bot.join(timeout=10)
    print(f'SLIME_GALLERY_RESTART_PASSED: {server}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, default=ROOT / 'run/slime-gallery-check')
    parser.add_argument('--cache-server', type=Path, default=ROOT / 'server')
    parser.add_argument('--port', type=int, default=25567)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('port must be between 1 and 65535')
    run(args.server.resolve(), args.cache_server.resolve(), args.port)

#!/usr/bin/env python3
"""Build/run/export the isolated issue #2 fixture server (Python standard library only)."""
import argparse
import hashlib
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PAPER = (
    "https://fill-data.papermc.io/v1/objects/"
    "d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c/paper-26.3-246.jar",
    "d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c",
)
MOJANG = (
    "https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar",
    "d052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64",
)


def download(path, artifact):
    url, checksum = artifact
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        request = urllib.request.Request(url, headers={"User-Agent": "Bake-Mechanisms-test-worlds/1"})
        partial = path.with_suffix(".part")
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        partial.replace(path)
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
            raise RuntimeError(f"Checksum mismatch: {path}. Remove that file and retry.")


def java():
    home = os.environ.get("JAVA_HOME")
    executable = str(Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if home else shutil.which("java")
    if not executable:
        raise RuntimeError("Java 25 is required. Set JAVA_HOME to your JDK 25 directory.")
    return executable


def prepare(server, accept_eula):
    if not accept_eula and not (server / "eula.txt").exists():
        raise RuntimeError("Pass --accept-eula after accepting https://aka.ms/MinecraftEULA for this local test server.")
    java()
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(wrapper), "build", "fixturesJar", "--console=plain"], cwd=ROOT, check=True)
    download(server / "paper.jar", PAPER)
    download(server / "cache" / "mojang_26.3.jar", MOJANG)
    (server / "plugins").mkdir(parents=True, exist_ok=True)
    for name in ("bake-mechanisms.jar", "bake-test-fixtures.jar"):
        shutil.copy2(ROOT / "build" / "libs" / name, server / "plugins" / name)
    if not (server / "eula.txt").exists():
        (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    properties = server / "server.properties"
    if not properties.exists():
        properties.write_text(
            "server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\n"
            "level-name=lobby\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
            'generator-settings={"layers":[{"block":"minecraft:stone","height":1}],"biome":"minecraft:plains"}\n'
            "spawn-protection=0\nview-distance=6\nsimulation-distance=6\n"
            "pause-when-empty-seconds=-1\nmax-tick-time=120000\n",
            encoding="utf-8",
        )
    print(f"Prepared {server}. The test server listens only on localhost:25566.")


class Console:
    def __init__(self, server):
        self.lines = queue.Queue()
        self.log = (server / "fixture-console.log").open("a", encoding="utf-8")
        self.process = subprocess.Popen(
            [java(), "-Djava.net.preferIPv4Stack=true", "-Xms512M", "-Xmx2G", "-jar", "paper.jar", "nogui"],
            cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
        )
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        for line in self.process.stdout:
            self.log.write(line)
            self.log.flush()
            self.lines.put(line.rstrip())

    def send(self, command):
        print(">", command, flush=True)
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def wait(self, marker, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RuntimeError(f"Server exited before {marker}; see fixture-console.log")
                continue
            if "FIXTURES_" in line or "ERROR" in line or marker in line:
                print(line, flush=True)
            if "FIXTURES_ERROR" in line or "Error occurred while enabling BakeTestFixtures" in line or "Command exception: /bakefixtures" in line:
                raise RuntimeError(line)
            if marker in line:
                return line
        raise RuntimeError(f"Timed out waiting for {marker}; see fixture-console.log")

    def stop(self):
        if self.process.poll() is None:
            self.send("stop")
            try:
                self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=30)
                raise RuntimeError("Server did not stop cleanly; do not export this run")
        self.reader.join(timeout=5)
        self.log.close()
        if self.process.returncode != 0:
            raise RuntimeError(f"Server exit code {self.process.returncode}; see fixture-console.log")


def smoke(server, farms):
    if (server / "plugins" / "BakeTestFixtures" / "manifest.yml").exists():
        raise RuntimeError("Smoke requires a fresh directory; use --server run/another-fixture-lab")
    console = Console(server)
    try:
        console.wait('Done (')
        console.send("bakefixtures create correctness")
        console.wait("FIXTURES_CREATED correctness")
        console.send(f"bakefixtures create performance {farms}")
        console.wait("FIXTURES_CREATED performance")
        console.send("bakefixtures check")
        console.wait("FIXTURES_CHECK_OK")
        console.send("bakefixtures list")
        console.send("bakefixtures observe all 1200")
        console.wait("FIXTURES_OBSERVING")
        console.wait("FIXTURES_OBSERVED", timeout=180)
        # Separate artificial collection diagnostic from natural production observations.
        console.send("bakefixtures observe blocks 400")
        console.wait("FIXTURES_OBSERVING")
        console.send("bakefixtures probe blocks")
        console.wait("FIXTURES_PROBE_INJECTED")
        console.wait("FIXTURES_OBSERVED", timeout=90)
        console.send("bakefixtures check")
        console.wait("FIXTURES_CHECK_OK")
        console.send("spark tps")
        console.send("save-all flush")
        console.wait("Saved the game")
    finally:
        console.stop()
    verify(server, farms + 2)
    print("Fixture smoke completed. This does not validate the unimplemented MVP lifecycle.")


def verify(server, expected=None):
    # Check that worlds and the builder manifest survive a real clean restart.
    console = Console(server)
    try:
        console.wait('Done (')
        console.send("bakefixtures hold")
        console.wait("FIXTURES_HELD")
        console.send("bakefixtures check")
        marker = "FIXTURES_CHECK_OK" if expected is None else f"FIXTURES_CHECK_OK farms={expected}"
        console.wait(marker)
    finally:
        console.stop()


def export(server, target):
    # Paper 26.3 shares level.dat and session.lock between dimensions. Export the
    # complete level root; copying only a dimension would omit its metadata.
    level = server / "lobby"
    if target.resolve().is_relative_to(level.resolve()):
        raise RuntimeError("Export destination must be outside the level being archived")
    if not (level / "level.dat").exists():
        raise RuntimeError(f"Missing saved level root: {level}")
    worlds = [level / "dimensions" / "minecraft" / name
              for name in ("bake_blocks", "bake_entities", "bake_performance")]
    for world in worlds:
        if not (world / "region").is_dir():
            raise RuntimeError(f"Missing saved fixture: {world}")
    manifest = server / "plugins" / "BakeTestFixtures" / "manifest.yml"
    if not manifest.exists():
        raise RuntimeError("Missing fixture manifest")
    # A nonblocking native file lock prevents packaging a world during a running server.
    locks = []
    try:
        for root in [level]:
            handle = (root / "session.lock").open("r+b")
            locks.append(handle)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        target.parent.mkdir(parents=True, exist_ok=True)
        partial = target.with_suffix(target.suffix + ".part")
        with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(level.rglob("*")):
                if file.is_file() and file.name != "session.lock":
                    archive.write(file, file.relative_to(server))
            archive.write(manifest, manifest.relative_to(server))
            for file in sorted(manifest.parent.glob("observations/*.yml")):
                archive.write(file, file.relative_to(server))
        partial.replace(target)
        print(f"Exported {target} ({target.stat().st_size} bytes)")
    finally:
        for handle in locks:
            handle.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "smoke", "verify", "run", "export"))
    parser.add_argument("--server", type=Path, default=ROOT / "run" / "fixture-lab")
    parser.add_argument("--farms", type=int, choices=range(1, 101), default=16, metavar="1..100")
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "build" / "test-worlds.zip")
    args = parser.parse_args()
    server = args.server.resolve()
    if args.action == "prepare":
        prepare(server, args.accept_eula)
    elif args.action == "smoke":
        smoke(server, args.farms)
    elif args.action == "verify":
        verify(server)
    elif args.action == "run":
        subprocess.run([java(), "-Xms512M", "-Xmx2G", "-jar", "paper.jar", "nogui"], cwd=server, check=True)
    else:
        export(server, args.output.resolve())


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))

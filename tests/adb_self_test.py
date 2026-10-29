#!/usr/bin/env python3
"""«إدارة التطبيقات» (AppsActivity, AdbSelf, Protected): the key adbd is given is adb's own form (encoded again here the way
adb-shell does), the signature is the one adbd checks, and the screen keeps its guards. The Java part needs javac and an
android.jar (ANDROID_JAR, else the SDK's under ANDROID_HOME); without them it is skipped.   Run: python3 tests/adb_self_test.py"""
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / "android/launcher/src/main/java/sa/urtv/home"
failed = 0


def check(name, ok, detail=""):
    global failed
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  -> " + str(detail)))
    failed += 0 if ok else 1


def adb_shell_encode(n, e):   # adb_shell.auth.keygen.encode_pubkey, from n and e
    r32 = 1 << 32
    n0inv = r32 - pow(n % r32, -1, r32)
    rr = (1 << (256 * 8)) ** 2 % n
    return struct.pack("<LL256s256sL", 64, n0inv, n.to_bytes(256, "little"), rr.to_bytes(256, "little"), e)


def shell_against_fake_adbd(classes, jar):
    """adbd with auth off (a TV with ro.adb.secure=0 answers CNXN at once) on 127.0.0.1:5555; returns what AdbSelf.shell printed
    and the problems the stand-in saw in what it was sent."""
    import socket
    import threading
    problems = []

    def recv(c):
        head = b""
        while len(head) < 24:
            got = c.recv(24 - len(head))
            if not got:
                raise EOFError
            head += got
        cmd, a0, a1, length, crc, magic = struct.unpack("<6I", head)
        data = b""
        while len(data) < length:
            data += c.recv(length - len(data))
        if magic != cmd ^ 0xFFFFFFFF or crc != sum(data) & 0xFFFFFFFF:
            problems.append(f"bad header {head.hex()}")
        return head[:4], a0, a1, data

    def send(c, cmd, a0, a1, data=b""):
        n = struct.unpack("<I", cmd)[0]
        c.sendall(struct.pack("<6I", n, a0, a1, len(data), sum(data) & 0xFFFFFFFF, n ^ 0xFFFFFFFF) + data)

    def serve(srv):
        c, _ = srv.accept()
        try:
            cmd, _, _, data = recv(c)
            if cmd != b"CNXN" or data != b"host::\0":
                problems.append(f"first {cmd} {data}")
            send(c, b"CNXN", 0x01000000, 4096, b"device::ro.product.name=test;\0")
            cmd, local, _, data = recv(c)
            if cmd != b"OPEN" or data != b"shell:pm disable-user --user 0 com.tcl.bi\0":
                problems.append(f"open {cmd} {data}")
            send(c, b"OKAY", 7, local)
            for part in (b"Package com.tcl.bi new state: ", b"disabled-user\n"):
                send(c, b"WRTE", 7, local, part)
                cmd, a0, a1, _ = recv(c)
                if (cmd, a0, a1) != (b"OKAY", local, 7):
                    problems.append(f"ack {cmd} {a0} {a1}")
            send(c, b"CLSE", 7, local)
            cmd, a0, a1, _ = recv(c)
            if (cmd, a0, a1) != (b"CLSE", local, 7):
                problems.append(f"close {cmd} {a0} {a1}")
        except Exception as e:  # noqa: BLE001
            problems.append(repr(e))
        finally:
            c.close()

    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind(("127.0.0.1", 5555))
    except OSError:
        return ("SKIP: port 5555 is in use here", [])
    srv.listen(1)
    t = threading.Thread(target=serve, args=(srv,), daemon=True)
    t.start()
    run = subprocess.run(["java", "-cp", classes + os.pathsep + str(jar), "sa.urtv.home.AdbSelfRun", "shell",
                          "pm disable-user --user 0 com.tcl.bi"], capture_output=True, text=True, timeout=30)
    t.join(5)
    srv.close()
    return (run.stdout or run.stderr[-300:], problems)


# ---- the screen's guards, read from the source
apps = (HOME / "AppsActivity.java").read_text(encoding="utf-8")
manifest = (ROOT / "android/launcher/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (ROOT / "android/launcher/build.gradle").read_text(encoding="utf-8")
prot = (HOME / "Protected.java").read_text(encoding="utf-8")
entry = manifest.split('android:name=".AppsActivity"', 1)[1].split("/>", 1)[0]
check("AppsActivity is not exported: no other app reaches the TV's ADB through it", 'android:exported="false"' in entry, entry)
check("the build copies keep.txt and core.txt in, the lists disable.sh enforces",
      "include 'keep.txt', 'core.txt', 'home-replaced.txt'; into 'lists'" in gradle and '"lists/keep.txt"' in prot and '"lists/core.txt"' in prot)
keep_homes = (ROOT / "optimize/lists/keep.txt").read_text(encoding="utf-8").split("# Home screens", 1)[1].split("\n\n", 1)[0]
check("every home screen keep.txt names is one Protected never turns back on",
      all(f'"{p}"' in prot for p in re.findall(r"^([a-z][\w.]+)", keep_homes, re.M)) and '"lists/home-replaced.txt"' in prot)
check("a list that could not be read protects everything", "if (names.isEmpty() || core.isEmpty()) return true;" in prot)
check("our apps, every home screen, the keyboard in use and persistent apps are protected",
      "c.getPackageName()" in prot and '"sa.urtv.screensaver"' in prot and "CATEGORY_HOME" in prot
      and "DEFAULT_INPUT_METHOD" in prot and "FLAG_PERSISTENT" in prot)
change = apps.split("private void change(", 1)[1].split("\n    }\n", 1)[0]
check("disabling asks twice and checks Protected again before the command; a home screen is not even turned back on",
      '"تأكيد؟"' in apps and "enable ? kept.homeScreen(pkg) : kept.has(a.info)" in change
      and change.index("kept.has(a.info)") < change.index("AdbSelf.shell("))
check("only a plain package name goes into the shell command",
      'pkg.matches("[A-Za-z0-9_.]+")' in change and change.index("pkg.matches") < change.index("AdbSelf.shell("))
check("the commands are pm disable-user and pm enable, nothing else",
      set(re.findall(r'"(pm [^"]*)"', apps)) == {"pm enable ", "pm disable-user --user 0 "}, re.findall(r'"(pm [^"]*)"', apps))
check("the screen asks for the PIN like «الرقابة الأبوية»", "ParentalPolicy.PARENTAL_SCREEN" in apps and "Parental.guard(" in apps)
adb = (HOME / "AdbSelf.java").read_text(encoding="utf-8")
check("the private key stays in the Android Keystore", '"AndroidKeyStore"' in adb and "getEncoded()" not in adb)
check("only the TV itself is asked", '"127.0.0.1"' in adb)

# ---- the key and the signature, on a JVM
jar = os.environ.get("ANDROID_JAR") or next(iter(sorted(Path(os.environ.get("ANDROID_HOME", "/nonexistent")).glob("platforms/*/android.jar"))), None)
if not (shutil.which("javac") and shutil.which("java") and jar and Path(jar).is_file()):
    print("SKIP AdbSelf key: no javac or no android.jar here")
else:
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-cp", str(jar), "-d", tmp, str(HOME / "AdbSelf.java"),
                        str(ROOT / "tests/AdbSelfRun.java")], check=True, capture_output=True)
        run = subprocess.run(["java", "-cp", tmp + os.pathsep + str(jar), "sa.urtv.home.AdbSelfRun"], capture_output=True, text=True)
        served = shell_against_fake_adbd(tmp, jar)
    out = run.stdout
    print("\n".join(line for line in out.splitlines() if not line.startswith("KEY ")))
    failed += 0 if run.returncode == 0 else 1
    key = [line.split()[1:] for line in out.splitlines() if line.startswith("KEY ")]
    if served[0].startswith("SKIP"):
        print(served[0])
    else:
        check("a shell command over the wire: CNXN, OPEN, OKAY for each WRTE, CLSE; every header's magic and checksum right",
              served == ("OUT Package com.tcl.bi new state: disabled-user\n", []), served)
    if key:
        n, e, enc = int(key[0][0], 16), int(key[0][1]), bytes.fromhex(key[0][2])
        check("the key is encoded exactly as adb-shell encodes it", enc == adb_shell_encode(n, e))
    else:
        check("AdbSelfRun printed the key", False, run.stderr[-500:])

sys.exit(1 if failed else 0)

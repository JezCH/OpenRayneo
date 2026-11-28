#!/usr/bin/env python3
"""Native Android UI evidence from the actual app, not a separate demo.
Fresh debug emulator only. No user data, credentials, external AI, recording,
Bluetooth pairing or signed distribution. Offline model weights may be absent.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.openrayneo.android"
TABS = ("홈", "AI", "기억", "도구", "기기")

def adb(*args, input_data=None, timeout=60, check=True):
    return subprocess.run(["adb", *args], input=input_data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=check, timeout=timeout).stdout

def wait_boot():
    for _ in range(120):
        if adb("shell", "getprop", "sys.boot_completed", timeout=10).strip() == b"1":
            return
        time.sleep(3)
    raise RuntimeError("emulator boot did not complete")

def tree():
    for _ in range(5):
        try:
            adb("shell", "rm", "-f", "/sdcard/optical-evidence.xml")
            adb("shell", "uiautomator", "dump", "/sdcard/optical-evidence.xml", timeout=30)
            raw = adb("exec-out", "cat", "/sdcard/optical-evidence.xml")
            return raw, ET.fromstring(raw)
        except (subprocess.SubprocessError, ET.ParseError):
            time.sleep(2)
    raise RuntimeError("unable to capture UI hierarchy")

def bounds(node):
    vals = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    return vals if len(vals) == 4 else [0, 0, 0, 0]

def labels(root):
    return {n.get("text", "") for n in root.iter("node")}

def find_tab(root, tab):
    nodes = [n for n in root.iter("node") if n.get("text") == tab or n.get("content-desc") == tab]
    # Header and tab may share the same label: the navigation is the lowest occurrence.
    if not nodes:
        raise RuntimeError(f"tab not found: {tab}, labels: {sorted(labels(root))[:50]}")
    return max(nodes, key=lambda n: bounds(n)[3])

def enter_tab(tab):
    raw, root = tree()
    x1, y1, x2, y2 = bounds(find_tab(root, tab))
    assert x2 > x1 and y2 > y1, f"invalid tab bounds {tab}"
    adb("shell", "input", "tap", str((x1 + x2)//2), str((y1 + y2)//2))
    time.sleep(1.5)

def launch(appearance, size, density, scale):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "wm", "size", size)
    adb("shell", "wm", "density", str(density))
    adb("shell", "settings", "put", "system", "font_scale", str(scale))
    xml = f'<?xml version="1.0" encoding="utf-8"?><map><boolean name="onboardingComplete" value="true"/><string name="appearance">{appearance}</string></map>'.encode()
    adb("shell", f"run-as {PACKAGE} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/companion_ui.xml'", input_data=xml)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(4)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    a = p.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    evidence = []
    try:
        wait_boot()
        adb("shell", "input", "keyevent", "82")
        for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            adb("shell", "settings", "put", "global", key, "0")
        adb("install", "-r", str(a.apk), timeout=180)
        for case, appearance, size, density, scale in (
            ("dark-393dp", "dark", "1080x2340", 440, 1.0),
            ("light-393dp", "light", "1080x2340", 440, 1.0),
            ("dark-320dp-large", "dark", "720x1600", 360, 2.0),
        ):
            launch(appearance, size, density, scale)
            for index, tab in enumerate(TABS):
                enter_tab(tab)
                raw, root = tree()
                name = f"{case}-{index+1:02d}"
                (a.out / f"{name}.xml").write_bytes(raw)
                png = adb("exec-out", "screencap", "-p")
                assert png.startswith(b"\x89PNG\r\n\x1a\n"), "screenshot PNG missing"
                (a.out / f"{name}.png").write_bytes(png)
                # The selected semantics, not the always-visible tab labels,
                # must change. Verify the foreground node belongs to our app.
                parents = {child: parent for parent in root.iter() for child in parent}
                current = find_tab(root, tab)
                assert current.get("package") == PACKAGE, f"{case}/{tab}: wrong foreground app"
                candidate = current
                selected = False
                while candidate is not None:
                    if candidate.get("selected") == "true":
                        selected = True
                        break
                    candidate = parents.get(candidate)
                if not selected:
                    raise RuntimeError(f"{case}/{tab}: tab selection was not observed")
                for title in TABS:
                    find_tab(root, title)
                visible = labels(root)
                required = {0: ("현재 활동",), 1: ("AI에 연결해야", "AI 연결 대기", "무엇을 도와드릴까요?", "질문 입력 가능", "연결 또는 설정 확인", "서비스 상태 미확인"),
                            2: ("새 기록", "현재 기록"), 3: ("자막 및 번역",),
                            4: ("RayNeo iO", "통신 및 기기 응답", "안경 사용 및 설정")}[index]
                if not any(any(expected in label for label in visible) for expected in required):
                    raise RuntimeError(f"{case}/{tab}: expected destination not visible; labels: {sorted(visible)[:50]}")
                evidence.append({"case": case, "tab": tab, "file": name + ".png",
                                 "native": True, "check": "actual app, selected tab, destination content"})
    finally:
        (a.out / "manifest.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
        try:
            (a.out / "android-runtime.log").write_bytes(adb("logcat", "-d", "-s", "AndroidRuntime", check=False))
            (a.out / "last-window.xml").write_bytes(adb("exec-out", "cat", "/sdcard/optical-evidence.xml", check=False))
        except (OSError, subprocess.SubprocessError):
            pass
    assert len(evidence) == 15, f"captured only {len(evidence)} screens"

if __name__ == "__main__":
    main()

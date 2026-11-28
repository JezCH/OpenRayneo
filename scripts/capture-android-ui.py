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

def matching_nodes(root, token):
    return [n for n in root.iter("node")
            if n.get("package") == PACKAGE
            and (token in n.get("text", "") or token in n.get("content-desc", ""))
            and bounds(n)[2] > bounds(n)[0] and bounds(n)[3] > bounds(n)[1]]

def editable_node(root):
    for n in root.iter("node"):
        if (n.get("package") == PACKAGE and n.get("class", "").endswith("EditText")
                and n.get("enabled") == "true" and bounds(n)[2] > bounds(n)[0]
                and bounds(n)[3] > bounds(n)[1]):
            return n
    return None

def scroll_content(root, size, direction=1):
    width, height = map(int, size.split("x"))
    # Use actual visible scroll container bounds, rather than device fractions
    # that may fall on a fixed bar, clipped item, or outside the content.
    candidates = [bounds(n) for n in root.iter("node")
                  if n.get("package") == PACKAGE and n.get("scrollable") == "true"]
    candidates = [b for b in candidates if b[2]-b[0] > width * .5 and b[3]-b[1] > height * .15]
    x1, y1, x2, y2 = max(candidates, key=lambda b: (b[2]-b[0])*(b[3]-b[1])) if candidates else (0, int(height*.26), width, int(height*.84))
    x = x1 + int((x2-x1)*.32)
    start = y1 + int((y2-y1)*.72)
    end = y1 + int((y2-y1)*.44)
    if direction < 0:
        start, end = end, start
    adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "650")
    time.sleep(.7)
    return [x, start, x, end]

def save_check(out, name, raw):
    (out / f"{name}.xml").write_bytes(raw)
    png = adb("exec-out", "screencap", "-p")
    assert png.startswith(b"\x89PNG\r\n\x1a\n"), "interaction screenshot PNG missing"
    (out / f"{name}.png").write_bytes(png)

def check_home(root, size, scale, out, case):
    targets = ("AI에게 질문", "회의 기록", "실시간 힌트", "한국어 호출", "영상 자막", "한영 통역")
    seen = {target for target in targets if matching_nodes(root, target)}
    initial = sorted(seen)
    trace = [{"initial": initial}]
    try:
        if scale == 1.0:
            assert set(targets[:4]) <= seen, f"first four shortcuts not visible: {initial}"
        for step in range(18):
            if len(seen) == len(targets):
                return {"initial": initial, "reachable": sorted(seen)}
            gesture = scroll_content(root, size, 1 if step < 12 else -1)
            raw, root = tree()
            visible = {target for target in targets if matching_nodes(root, target)}
            seen.update(visible)
            trace.append({"step": step+1, "gesture": gesture, "visible": sorted(visible),
                          "labels": sorted(labels(root))[:45]})
            (out / f"{case}-home-scroll-last.xml").write_bytes(raw)
        raise RuntimeError(f"missing reachable home shortcuts: {sorted(set(targets) - seen)}")
    finally:
        (out / f"{case}-home-scroll-trace.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2))
        print("HOME_SCROLL_TRACE " + case + " " + json.dumps(trace, ensure_ascii=False))
        if len(seen) != len(targets):
            raw, _ = tree()
            save_check(out, case + "-home-scroll-failure", raw)

def check_ai(root, size, scale, out, case):
    setup = bool(matching_nodes(root, "AI 연결 설정"))
    initial = setup
    trace = [{"initial_setup": setup}]
    try:
        for step in range(19):
            if setup and editable_node(root) is not None:
                raw, root = tree()
                assert editable_node(root) is not None
                save_check(out, case + "-ai-input", raw)
                if scale == 1.0:
                    assert initial, "setup action not visible on entry"
                return {"setup_initial": initial, "setup_reachable": True, "editable_input_reachable": True}
            gesture = scroll_content(root, size, 1 if step < 12 else -1)
            raw, root = tree()
            setup = setup or bool(matching_nodes(root, "AI 연결 설정"))
            trace.append({"step": step+1, "gesture": gesture, "setup": setup,
                          "editable": editable_node(root) is not None, "labels": sorted(labels(root))[:45]})
            (out / f"{case}-ai-scroll-last.xml").write_bytes(raw)
        raise RuntimeError(f"AI setup/input not reachable: setup={setup}, input={editable_node(root) is not None}")
    finally:
        (out / f"{case}-ai-scroll-trace.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2))
        print("AI_SCROLL_TRACE " + case + " " + json.dumps(trace, ensure_ascii=False))
        if not setup or editable_node(root) is None:
            raw, _ = tree()
            save_check(out, case + "-ai-scroll-failure", raw)

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
    checks = []
    failures = []
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
                            2: ("기억 및 음성 기록",), 3: ("기능 라이브러리",),
                            4: ("RayNeo iO", "통신 및 기기 응답", "안경 사용 및 설정")}[index]
                if not any(any(expected in label for label in visible) for expected in required):
                    raise RuntimeError(f"{case}/{tab}: expected destination not visible; labels: {sorted(visible)[:50]}")
                evidence.append({"case": case, "tab": tab, "file": name + ".png",
                                 "native": True, "check": "actual app, selected tab, destination content"})
                if index in (0, 1):
                    check = "home-shortcuts" if index == 0 else "ai-setup-input"
                    try:
                        result = (check_home(root, size, scale, a.out, case) if index == 0
                                  else check_ai(root, size, scale, a.out, case))
                        checks.append({"case": case, "check": check, "passed": True, **result})
                    except (AssertionError, RuntimeError, subprocess.SubprocessError) as exc:
                        failure = {"case": case, "check": check, "passed": False, "error": str(exc)}
                        checks.append(failure)
                        failures.append(failure)
                        print("INTERACTION_FAILURE " + json.dumps(failure, ensure_ascii=False))
    finally:
        (a.out / "manifest.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
        (a.out / "interaction-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2))
        print("NATIVE_INTERACTION_CHECKS " + json.dumps(checks, ensure_ascii=False))
        try:
            (a.out / "android-runtime.log").write_bytes(adb("logcat", "-d", "-s", "AndroidRuntime", check=False))
            (a.out / "last-window.xml").write_bytes(adb("exec-out", "cat", "/sdcard/optical-evidence.xml", check=False))
        except (OSError, subprocess.SubprocessError):
            pass
    assert len(evidence) == 15, f"captured only {len(evidence)} screens"
    assert len(checks) == 6, f"completed only {len(checks)} interaction checks"
    assert not failures, f"{len(failures)} interaction checks failed: {failures}"

if __name__ == "__main__":
    main()

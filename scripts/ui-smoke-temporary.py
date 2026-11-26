"""Temporary emulator test. No physical glasses, real accounts or user records."""
import json, pathlib, re, subprocess, time, xml.etree.ElementTree as ET
OUT = pathlib.Path("ui-smoke-output")
OUT.mkdir(exist_ok=True)
results = []
def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/smoke-ui.xml", check=False)
        data = adb("exec-out", "cat", "/sdcard/smoke-ui.xml", check=False)
        try:
            return ET.fromstring(data), data
        except ET.ParseError:
            time.sleep(1)
    raise RuntimeError("Could not read actual emulator UI")
def labels(root):
    return [v for node in root.iter("node") for v in (node.get("text",""),node.get("content-desc","")) if v]
def screenshot(name):
    _, data = dump()
    (OUT / (name + ".xml")).write_bytes(data)
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
def ensure(expected):
    for _ in range(4):
        root, _ = dump()
        if any(expected in label for label in labels(root)): return
        time.sleep(1)
    raise AssertionError("Missing visible text: " + expected + "; actual: " + repr(labels(root)[:35]))
def find_click(root, target):
    parents = {child: parent for parent in root.iter() for child in parent}
    candidates = []
    for node in root.iter("node"):
        values = [node.get("text",""), node.get("content-desc","")]
        if not any(target in value for value in values): continue
        cursor = node
        while cursor is not None and cursor.get("clickable") != "true":
            cursor = parents.get(cursor)
        if cursor is None or cursor.get("enabled") == "false": continue
        bounds = list(map(int, re.findall(r"\d+", cursor.get("bounds",""))))
        if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]: continue
        candidates.append((0 if target in values else 1, bounds))
    if not candidates: return False
    _, (x1,y1,x2,y2) = min(candidates, key=lambda item:item[0])
    adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.55)
    return True
def tap(target, scroll=False):
    directions = [None] + (["down"]*6+["up"]*10 if scroll else [])
    for direction in directions:
        if direction == "down": adb("shell","input","swipe","540","1750","540","550","300")
        elif direction == "up": adb("shell","input","swipe","540","550","540","1750","300")
        time.sleep(.25)
        root,_ = dump()
        if find_click(root,target): return
    raise AssertionError("Cannot tap: " + target + "; actual: " + repr(labels(root)[:35]))
def back():
    adb("shell","input","keyevent","4")
    time.sleep(.55)
def test(name, fn):
    try:
        fn()
        screenshot(name)
        results.append({"screen":name,"result":"pass"})
    except Exception as exc:
        results.append({"screen":name,"result":"fail","error":str(exc)})
        try: screenshot(name+"-failure")
        except Exception: pass
        raise
    finally:
        (OUT/"results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2),encoding="utf-8")
def route(name, menu, expected, parent):
    def action():
        tap(menu, scroll=True)
        ensure(expected)
    test(name, action)
    back()
    ensure(parent)
time.sleep(3)
root,_=dump()
if any("나중에 설정" in value for value in labels(root)): tap("나중에 설정")
test("01-home",lambda: ensure("현재 활동"))
tap("도구")
test("02-tools",lambda: ensure("자막 및 번역"))
route("03-media","유튜브·영상 자막","시작 전 확인 필요","도구")
route("04-captions","주변 대화 자막","주변 대화 자막","도구")
route("05-translation","주변 대화 번역","한국어 ↔ 영어","도구")
route("06-korean-wake","한국어 호출","호출 연결 진단","도구")
route("07-teleprompter","프롬프터","프롬프터 원고함","도구")
route("08-navigation","길 안내","길 안내","도구")
route("09-content","할 일 · 일정 · 날씨","홈 화면 및 콘텐츠","도구")
route("10-clock","타이머 · 스톱워치","타이머 시작","도구")
route("11-display","안경 화면 및 오디오","공식 앱 / OpenRayneo 전환","도구")
tap("설정")
test("12-settings",lambda: ensure("앱 업데이트"))
route("13-update","앱 업데이트","현재 버전:","설정")
route("14-summary-model","기록 요약 모델","기록 요약 모델 설정","설정")
route("15-computer","컴퓨터 Codex","컴퓨터 연결 설정","설정")
route("16-agent","AI 연결 · DSH Go","DSH Go 및 외부 Agent","설정")
def voice():
    tap("음성·기억 설정",scroll=True)
    ensure("변경한 음성·기억 설정 저장")
    tap("음성 모델",scroll=True)
    ensure("안경 음성 인식 모델")
    ensure("변경한 음성·기억 설정 저장")
test("17-voice-model",voice)
back(); ensure("설정")
route("18-about","About You · 개인화 정보","나에 대해 · About You","설정")
route("19-person","목소리 및 인물","목소리 및 인물","설정")
route("20-migration","기존 기록 가져오기","기존 앱의 기록 가져오기","설정")
route("21-device-settings","안경 설정","안경 설정 읽기","설정")
route("22-notifications","휴대폰 알림","휴대폰 알림 전달","설정")
route("23-diagnostics","기기 및 진단","알림 읽기","설정")
back(); ensure("도구")
tap("기기")
test("24-device",lambda: ensure("안경 연결"))
route("25-glyphs","한글 표시 검사","한글 표시 검사","기기")
tap("AI")
test("26-ai",lambda: ensure("AI에 연결해야 질문할 수 있습니다"))
tap("기억")
test("27-records",lambda: ensure("새 기록"))
# Repeat a few high-priority screens at a larger Android font size.
adb("shell","settings","put","system","font_scale","1.3")
time.sleep(2)
tap("도구")
test("28-tools-large-text",lambda: ensure("자막 및 번역"))
tap("유튜브·영상 자막")
test("29-media-large-text",lambda: ensure("시작 전 확인 필요"))
back()
print(json.dumps(results, ensure_ascii=False, indent=2))

#!/usr/bin/env python3
"""Regression tests for Anime Home on the Mi TV, driven over adb.

usage: python3 -I tools/regression.py [--device <ip:port>] [--reboot]
(default device: $ANIME_TV_DEVICE, else the first device `adb devices` lists)

Safety: every key press is preceded by a check that Anime Home is the foreground app;
if it is not, the run aborts instead of pressing keys into some other app.
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

import os
DEV = os.environ.get("ANIME_TV_DEVICE") or next(
    (l.split()[0] for l in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
     if l.strip().endswith("device")), "")
PKG = "com.vishal.animehome"
HOME = PKG + "/.Home"
args = sys.argv[1:]
if "--device" in args:
    DEV = args[args.index("--device") + 1]
REBOOT = "--reboot" in args

results = []


def adb(*a, timeout=60):
    p = subprocess.run(["adb", "-s", DEV, *a], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    return p.stdout + p.stderr


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""), flush=True)
    return ok


def top():
    m = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys activity activities | grep -m1 topResumedActivity"))
    return m.group(1) if m else ""


def key(code):
    if not top().startswith(PKG):
        print(f"ABORT: Anime Home is not in front ({top()}); not sending {code}")
        summary()
        sys.exit(2)
    sh(f"input keyevent {code}")


class Node:
    def __init__(self, e):
        self.text = e.get("text", "")
        self.cls = e.get("class", "")
        self.pkg = e.get("package", "")
        self.focusable = e.get("focusable") == "true"
        self.focused = e.get("focused") == "true"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", e.get("bounds", "[0,0][0,0]")))
        self.l, self.t, self.r, self.b = x1, y1, x2, y2
        self.w, self.h = x2 - x1, y2 - y1


def dump():
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml")
        if xml.strip().startswith("<?xml"):
            return [Node(e) for e in ET.fromstring(xml[xml.index("<hierarchy"):]).iter("node")]
        time.sleep(1)
    return []


def screen():
    """Returns the app-row tiles, the focused node, the label above the row and shelf info."""
    nodes = [n for n in dump() if n.pkg == PKG]
    tiles = sorted([n for n in nodes if n.cls.endswith("FrameLayout") and n.focusable and 110 <= n.w <= 160
                    and abs(n.w - n.h) < 6 and n.t > 650], key=lambda n: n.l)
    row_top = min((t.t for t in tiles), default=760)
    focused = next((n for n in nodes if n.focused), None)
    scroll = next((n for n in nodes if n.cls.endswith("HorizontalScrollView")), None)
    # The app name label sits directly on top of the scrolling app row.
    edge = scroll.t if scroll else row_top
    labels = [n for n in nodes if n.cls.endswith("TextView") and n.text and abs(n.b - edge) <= 6]
    label = labels[0].text if labels else ""
    header = next((n.text for n in nodes if n.text.startswith("雷") and "TOP 5" in n.text), "")
    cards = [n for n in nodes if n.cls.endswith("LinearLayout") and n.focusable and n.b < row_top - 40 and n.h > 200]
    return dict(nodes=nodes, tiles=tiles, focused=focused, label=label, header=header, cards=cards, row_top=row_top)


def summary():
    passed = sum(1 for r in results if r[1])
    print(f"\n{passed}/{len(results)} checks passed")
    for name, ok, detail in results:
        if not ok:
            print(f"  FAILED: {name} {detail}")


def wait_for_device(limit=150):
    end = time.time() + limit
    while time.time() < end:
        subprocess.run(["adb", "connect", DEV], capture_output=True, timeout=20)
        if "1" in sh("getprop sys.boot_completed", timeout=10).strip():
            return True
        time.sleep(5)
    return False


# ------------------------------------------------------------------ static state

def static_checks(stage):
    v = re.search(r"versionName=(\S+)", sh(f"dumpsys package {PKG} | grep -m1 versionName"))
    check(f"[{stage}] Anime Home installed", v, v.group(1) if v else "missing")
    home = re.search(r"packageName=(\S+)", sh("cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME"))
    check(f"[{stage}] Anime Home is the default home", home and home.group(1) == PKG, home.group(1) if home else "none")
    wm = sh("wm size; wm density")
    check(f"[{stage}] UI size 1920x1080 on 4K panel", "Physical size: 3840x2160" in wm and "Override size: 1920x1080" in wm)
    check(f"[{stage}] no density override (native 320, not zoomed)", "Physical density: 320" in wm and "Override density" not in wm,
          " ".join(l.strip() for l in wm.splitlines() if "density" in l))
    live = sh("pm list packages com.mitv.livetv")
    check(f"[{stage}] Mi Live TV installed (HDMI player)", "package:com.mitv.livetv\n" in live + "\n" or live.strip().endswith("com.mitv.livetv"))


# ------------------------------------------------------------------ UI checks

def ui_checks():
    sh("logcat -c")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    check("Home button opens Anime Home", top().startswith(PKG), top())
    sh(f"am start -n {HOME}")  # onNewIntent: back to first app
    time.sleep(2)
    s = screen()
    texts = [n.text for n in s["nodes"]]
    check("Kakashi theme shown", any("KAKASHI HATAKE" in t for t in texts))
    check("other themes / theme chips removed", not any(t in ("↻", "月", "油", "渦", "烏") for t in texts))
    check("app row has icons", len(s["tiles"]) >= 8, f"{len(s['tiles'])} tiles on screen")
    for want in ("HDMI 1", "HDMI 2", "HDMI 3", "Settings"):
        check(f"button '{want}' present", any(want in t for t in texts))
    check("first app focused after Home", s["focused"] is not None and s["tiles"] and s["focused"].l == s["tiles"][0].l,
          s["label"])

    # Walk the whole app row and record what each app shows.
    seen = {}
    for _ in range(len(s["tiles"]) + 6):
        time.sleep(0.9)
        s = screen()
        f = s["focused"]
        if s["label"] in seen:
            break
        seen[s["label"]] = (len(s["cards"]), s["header"], f)
        key("KEYCODE_DPAD_RIGHT")
    print("    app row:", ", ".join(f"{k}={v[0]}" for k, v in seen.items()))

    shelf_apps = {"Netflix": "NETFLIX", "Prime Video": "PRIME VIDEO", "JioHotstar": "JIOHOTSTAR"}
    for label, (cards, header, f) in seen.items():
        lw = label.lower()
        expects = any(k in lw for k in ("netflix", "prime", "hotstar", "sony", "zee", "mx player"))
        if expects:
            check(f"'{label}' shows a 5-title shelf", cards == 5 and label.upper().split()[0] in header.upper(),
                  f"{cards} cards, header '{header}'")
        else:
            check(f"'{label}' shows no shelf", cards == 0, f"{cards} cards")
        if f is not None:
            # Focused button is enlarged (~1.1x of 128px) and sits fully inside the row: nothing clipped.
            check(f"'{label}' focus zoom ~1.1x", 136 <= f.w <= 145, f"{f.w}px")
    _ = shelf_apps

    # Shelf navigation from Netflix.
    sh(f"am start -n {HOME}")
    time.sleep(1.5)
    for _ in range(12):
        s = screen()
        if s["label"].lower().startswith("netflix"):
            break
        key("KEYCODE_DPAD_RIGHT")
        time.sleep(0.6)
    time.sleep(1)
    s = screen()
    check("reached Netflix with shelf open", s["label"].lower().startswith("netflix") and len(s["cards"]) == 5, s["label"])
    titles = [n.text for n in s["nodes"] if n.cls.endswith("TextView") and n.text and s["cards"]
              and any(c.l <= n.l and n.r <= c.r + 2 and c.t <= n.t and n.b <= c.b + 2 for c in s["cards"])]
    check("shelf cards have titles and details", len(titles) >= 10, "; ".join(titles[:4]))
    key("KEYCODE_DPAD_UP")
    time.sleep(0.6)
    s2 = screen()
    in_shelf = s2["focused"] is not None and s2["focused"].b < s2["row_top"] - 40
    check("Up moves into the posters", in_shelf)
    before = s2["focused"].l if s2["focused"] else -1
    key("KEYCODE_DPAD_RIGHT")
    time.sleep(0.5)
    s3 = screen()
    check("Right moves between posters", s3["focused"] is not None and s3["focused"].l > before)
    key("KEYCODE_DPAD_DOWN")
    time.sleep(0.6)
    s4 = screen()
    check("Down returns to Netflix", s4["label"].lower().startswith("netflix") and s4["focused"] in s4["tiles"] or
          (s4["focused"] is not None and s4["focused"].t > s4["row_top"] - 10 and s4["label"].lower().startswith("netflix")),
          s4["label"])
    key("KEYCODE_DPAD_UP")
    time.sleep(0.5)
    key("KEYCODE_BACK")
    time.sleep(0.6)
    s5 = screen()
    check("Back from posters returns to Netflix", s5["label"].lower().startswith("netflix") and s5["focused"] is not None
          and s5["focused"].t >= s5["row_top"] - 10, s5["label"])
    check("Back did not leave the home screen", top().startswith(PKG), top())
    key("KEYCODE_DPAD_DOWN")
    time.sleep(0.8)
    s6 = screen()
    check("moving to the buttons row hides the shelf", len(s6["cards"]) == 0 and s6["focused"] is not None
          and s6["focused"].t > s6["row_top"] + 100, f"{len(s6['cards'])} cards")

    crash = sh("logcat -d -b crash")
    check("no crashes during the run", PKG not in crash, "crash log mentions Anime Home" if PKG in crash else "")
    pss = re.search(r"TOTAL PSS:\s+(\d+)", sh(f"dumpsys meminfo {PKG}"))
    check("memory use under 80 MB", pss and int(pss.group(1)) < 80000, f"{int(pss.group(1)) // 1024} MB" if pss else "")


# ------------------------------------------------------------------ intents (resolve only, nothing is opened)

def intent_checks():
    hdmi = sh("cmd package query-activities -a android.intent.action.VIEW "
              "-d 'content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHW2' "
              "-t vnd.android.cursor.item/channel")
    check("HDMI buttons resolve to Mi Live TV", "com.mitv.livetv" in hdmi)
    st = sh("cmd package query-activities -a android.settings.SETTINGS")
    check("Settings button resolves", "packageName=" in st)

    q = {"query": "query { " + " ".join(
        f'{c}: popularTitles(country: IN, first: 5, filter: {{packages: ["{c}"]}}, sortBy: POPULAR) '
        f'{{ edges {{ node {{ offers(country: IN, platform: WEB, filter: {{packages: ["{c}"]}}) '
        f'{{ deeplinkTv: deeplinkURL(platform: ANDROID_TV) }} }} }} }}' for c in ("nfx", "prv", "jhs", "zee", "snl")) + " }"}
    try:
        req = urllib.request.Request("https://apis.justwatch.com/graphql", json.dumps(q).encode(),
                                     {"Content-Type": "application/json", "User-Agent": "AnimeHome-tests"})
        data = json.load(urllib.request.urlopen(req, timeout=15))["data"]
        check("JustWatch suggestions API reachable", True)
    except Exception as e:
        check("JustWatch suggestions API reachable", False, str(e))
        return
    pkgs = {"nfx": "com.netflix.ninja", "prv": "com.amazon.amazonvideo.livingroom", "jhs": "in.startv.hotstar",
            "zee": "com.graymatrix.did", "snl": "com.sonyliv"}
    for code, pkg in pkgs.items():
        link = None
        for e in data[code]["edges"]:
            for o in e["node"]["offers"] or []:
                link = link or o.get("deeplinkTv")
        m = re.match(r"intent://([^#]*)#Intent;(.*)end", link or "")
        if not m:
            check(f"{pkg} deep link available", False, "no link")
            continue
        kv = dict(x.split("=", 1) for x in m.group(2).split(";") if "=" in x)
        uri = kv.get("scheme", "https") + "://" + m.group(1)
        out = sh(f"cmd package query-activities -a {kv.get('action', 'android.intent.action.VIEW')} -d '{uri}' -p {pkg}")
        check(f"{pkg} opens shows from the shelf", "packageName=" + pkg in out, uri[:60])


# ------------------------------------------------------------------ main

subprocess.run(["adb", "connect", DEV], capture_output=True, timeout=20)
static_checks("now")
intent_checks()
ui_checks()

if REBOOT:
    print("\nRebooting the TV to check everything survives a restart...", flush=True)
    sh("reboot", timeout=15)
    time.sleep(20)
    if check("TV came back after reboot", wait_for_device()):
        time.sleep(15)
        static_checks("after reboot")
        check("[after reboot] Anime Home on screen after boot", top().startswith(PKG), top())
        ui_checks()

summary()
sys.exit(0 if all(r[1] for r in results) else 1)

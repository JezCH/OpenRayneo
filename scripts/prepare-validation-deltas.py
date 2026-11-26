#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Produce direct-to-current patches from exact, verified previous APKs.
No publication; only the explicit existing publication job can release these files.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
SIGNER = "ed9341632021ba4129608624a86309155b8373f01ec9b16dff2e5863eb440965"
PACKAGE = "com.openrayneo.android.validation"
MAX = 1900 * 1024 * 1024
LIMIT = 3

def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=900)

def digest(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def load(directory, tools):
    m = json.loads((directory / "update-manifest.json").read_text())
    apk = directory / "OpenRayneo-validation.apk"
    assert m["schemaVersion"] == 1 and m["channel"] == "validation" and m["packageName"] == PACKAGE
    assert m["signerSha256"] == SIGNER and m["abi"] == "arm64-v8a"
    assert m["apkAssetName"] == apk.name
    assert 0 < apk.stat().st_size <= MAX and m["apkSize"] == apk.stat().st_size
    assert m["apkSha256"] == digest(apk)
    certs = run(str(tools / "apksigner"), "verify", "--print-certs", str(apk))
    assert re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", certs, re.M) == [SIGNER]
    badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
    assert re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M).groups() == (PACKAGE, str(m["versionCode"]))
    return m, apk

def baselines(code):
    repo = os.environ["GH_REPO"]
    assert repo == "JezCH/OpenRayneo"
    # Prefer published versions for long-lived clients. Include recent private delivery artifacts
    # for development builds even before the public channel has its first release.
    releases = json.loads(run("gh", "api", f"repos/{repo}/releases?per_page=30"))
    tags = []
    for row in releases:
        match = re.fullmatch(r"validation-v(\d+)", row.get("tag_name", ""))
        if match and row.get("prerelease") and not row.get("draft") and int(match[1]) < code:
            tags.append((int(match[1]), row["tag_name"]))
    for _, tag in sorted(tags, reverse=True)[:LIMIT]:
        yield ("release", tag)
    runs = json.loads(run("gh", "api", f"repos/{repo}/actions/workflows/android-apk.yml/runs?branch=wearable%2Fvalidation-package&status=success&per_page=10"))
    for row in runs["workflow_runs"]:
        if row["event"] == "push" and row["head_branch"] == "wearable/validation-package" and int(row["run_number"]) + 1000 < code:
            yield ("artifact", str(row["id"]))

def main(directory, tools):
    current, target = load(directory, tools)
    code = current["versionCode"]
    patches = []
    seen = set()
    try:
        sources = list(baselines(code))
    except (subprocess.SubprocessError, ValueError, KeyError) as e:
        print("No baseline discovery; full update remains available:", type(e).__name__, flush=True)
        sources = []
    for kind, ref in sources:
        if len(patches) >= LIMIT:
            break
        patch = None
        try:
            with tempfile.TemporaryDirectory(prefix="delta-base-") as tmp:
                folder = Path(tmp)
                if kind == "release":
                    run("gh", "release", "download", ref, "--repo", os.environ["GH_REPO"], "--pattern", "OpenRayneo-validation.apk", "--pattern", "update-manifest.json", "--dir", tmp)
                else:
                    run("gh", "run", "download", ref, "--repo", os.environ["GH_REPO"], "--pattern", "openrayneo-validation-update-*", "--dir", tmp)
                manifests = list(folder.rglob("update-manifest.json"))
                assert len(manifests) == 1
                previous, base = load(manifests[0].parent, tools)
                version = previous["versionCode"]
                if version >= code or version in seen:
                    continue
                seen.add(version)
                patch = directory / f"delta-{version}-to-{code}.orp"
                metrics = json.loads(run("python3", str(ROOT / "apk-delta.py"), "create", str(base), str(target), str(patch)))
                if patch.stat().st_size >= current["apkSize"] * 0.85:
                    patch.unlink()
                    print(f"Skipping inefficient delta {version} to {code}", flush=True)
                    continue
                restored = folder / "restored.apk"
                run("python3", str(ROOT / "apk-delta.py"), "restore", str(base), str(restored), str(patch))
                assert restored.stat().st_size == current["apkSize"] and digest(restored) == current["apkSha256"]
                certs = run(str(tools / "apksigner"), "verify", "--print-certs", str(restored))
                assert re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", certs, re.M) == [SIGNER]
                patches.append({"algorithm": "raw-copy-v1", "baseVersionCode": version,
                    "baseApkSize": previous["apkSize"], "baseApkSha256": previous["apkSha256"],
                    "patchAssetName": patch.name, "patchSize": patch.stat().st_size, "patchSha256": digest(patch)})
                print(f"Verified delta {version} to {code}: " + json.dumps(metrics), flush=True)
        except (OSError, ValueError, AssertionError, KeyError, AttributeError, subprocess.SubprocessError) as e:
            if patch is not None:
                patch.unlink(missing_ok=True)
            print(f"Baseline {kind} {ref} unavailable/invalid; full APK retained ({type(e).__name__}): {str(e)[:400]}", flush=True)
    current["deltas"] = patches
    (directory / "update-manifest.json").write_text(json.dumps(current, indent=2) + "\n")
    print(f"Verified direct delta count: {len(patches)}", flush=True)

if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))

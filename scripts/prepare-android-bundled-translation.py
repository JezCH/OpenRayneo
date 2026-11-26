#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bundle unchanged Mozilla translation weights from the existing pinned catalog."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "android/app/src/main/assets/mozilla-media-models.json"
LICENSE = ROOT / "ThirdParty/MozillaTranslation/LICENSE"
def digest(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()
def entries():
    catalog = json.loads(CATALOG.read_text())
    base = catalog["baseUrl"]
    assert base == "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
    for route in catalog["routes"]:
        directory = route["directory"]
        assert re.fullmatch(r"models/[A-Za-z0-9_./-]+/exported/", directory) and not any(p in (".", "..") for p in directory.split("/"))
        for role, name, wire, size, sha, generation, *_ in route["files"]:
            assert re.fullmatch(r"[A-Za-z0-9_.-]+\.(bin|spm)", name)
            assert re.fullmatch(r"[a-f0-9]{64}", sha) and str(generation).isdigit()
            assert 0 < size <= 134217728 and 0 < wire <= 134217728
            raw = catalog.get("rawAttachments", {}).get(sha)
            if raw:
                assert re.fullmatch(r"https://firefox-settings-attachments\.cdn\.mozilla\.net/main-workspace/translations-models/[A-Za-z0-9_-]+\.(bin|spm)", raw)
            yield sha + Path(name).suffix, raw or f"{base}/{directory}{name}.gz?generation={generation}", size if raw else wire, size, sha, not raw
def prepare(output):
    dest = output / "bundled-translation"
    dest.mkdir(parents=True, exist_ok=True)
    for name, url, wire, size, sha, compressed in entries():
        target = dest / name
        if target.is_file() and target.stat().st_size == size:
            with target.open("rb") as stream:
                if digest(stream) == sha:
                    continue
        source = dest / (name + ".download")
        part = dest / (name + ".part")
        try:
            subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3",
                            "--connect-timeout", "30", "--max-time", "600", "--header", "Accept-Encoding: identity",
                            "--output", str(source), url], check=True)
            assert source.stat().st_size == wire, f"Incorrect transport size: {url}"
            with (gzip.open(source, "rb") if compressed else source.open("rb")) as stream, part.open("wb") as out:
                total = 0
                while chunk := stream.read(65536):
                    total += len(chunk)
                    assert total <= size, f"Incorrect decompressed size: {name}"
                    out.write(chunk)
            with part.open("rb") as stream:
                assert part.stat().st_size == size and digest(stream) == sha, f"Incorrect model hash: {name}"
            part.replace(target)
            print("Bundled Mozilla", name, size, flush=True)
        finally:
            source.unlink(missing_ok=True)
            part.unlink(missing_ok=True)
    shutil.copyfile(LICENSE, dest / "LICENSE")
    (dest / "SOURCE.txt").write_text("Unchanged Mozilla Firefox Translations models (MPL-2.0).\nExact source URLs, generations and hashes: assets/mozilla-media-models.json.\nhttps://github.com/mozilla/translations\n", encoding="utf-8")
def verify(apk):
    with zipfile.ZipFile(apk) as package:
        prefix = "assets/bundled-translation/"
        assert package.read(prefix + "LICENSE") == LICENSE.read_bytes()
        assert package.getinfo(prefix + "SOURCE.txt").file_size > 0
        assert package.read("assets/mozilla-media-models.json") == CATALOG.read_bytes()
        for name, _, _, size, sha, _ in entries():
            assert package.getinfo(prefix + name).file_size == size
            with package.open(prefix + name) as stream:
                assert digest(stream) == sha, name
    print("Verified all pinned Mozilla translation model assets", flush=True)
if __name__ == "__main__":
    assert len(sys.argv) == 3 and sys.argv[1] in ("prepare", "verify")
    (prepare if sys.argv[1] == "prepare" else verify)(Path(sys.argv[2]))

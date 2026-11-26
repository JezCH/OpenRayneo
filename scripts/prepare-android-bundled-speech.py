#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Package the existing Android speech models, with source revision and redistribution notices.
Weights are generated build assets, never committed. Resolve one revision per repository;
verify LFS hashes and sizes before accepting cached or newly downloaded weights.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

MODELS = [
    ("asr/sense-voice-2024-07-17", "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
     ["model.int8.onnx", "tokens.txt"], "FunASR Model Open Source License Agreement v1.1",
     "https://raw.githubusercontent.com/modelscope/FunASR/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE"),
    ("asr/zipformer-ko-2024-06-16", "k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16",
     ["encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"],
     "Apache-2.0; original checkpoint johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12", None),
    ("asr/nemotron-3.5-streaming-560ms-int8", "apbaxel/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-int8",
     ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"],
     "NVIDIA Open Model Development and Weight License 1.1",
     "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/main/OpenMDW-1.1.txt"),
    ("asr/ko-kws-zipformer-tiny", "kairess/ko-kws-zipformer-tiny",
     ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.model"],
     "Apache-2.0", None),
]
ROOT = Path(__file__).resolve().parent.parent
def digest(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()
def fetch(url, target, size=None, sha=None):
    target.parent.mkdir(parents=True, exist_ok=True)
    if size and sha and target.is_file() and target.stat().st_size == size and digest(target) == sha:
        return
    part = target.with_name(target.name + ".part")
    subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3",
                    "--connect-timeout", "30", "--max-time", "1800", "--output", str(part), url], check=True)
    assert part.stat().st_size > 0 and (size is None or part.stat().st_size == size), f"Bad model size: {url}"
    assert sha is None or digest(part) == sha, f"Bad model digest: {url}"
    part.replace(target)
def prepare(output):
    for folder, repo, names, license_name, license_url in MODELS:
        url = "https://huggingface.co/" + repo
        with urllib.request.urlopen("https://huggingface.co/api/models/" + repo + "?blobs=true", timeout=60) as response:
            info = json.load(response)
        revision = info["sha"]
        assert len(revision) == 40 and all(c in "0123456789abcdef" for c in revision)
        siblings = {row["rfilename"]: row for row in info["siblings"]}
        dest = output / "bundled-speech" / folder
        entries = {}
        for name in names:
            row = siblings[name]
            lfs = row.get("lfs") or {}
            size = row.get("size", lfs.get("size"))
            assert isinstance(size, int) and size > 0
            if name.endswith(".onnx"):
                assert lfs.get("sha256"), f"Missing upstream LFS hash: {repo}/{name}"
            path = dest / name
            fetch(f"{url}/resolve/{revision}/{name}", path, size, lfs.get("sha256"))
            entries[name] = {"size": size, "sha256": digest(path)}
        # Keep the model's upstream card and notices alongside the unchanged weights.
        for name in siblings:
            if "/" not in name and (name.upper().startswith(("LICENSE", "NOTICE", "COPYING", "OPENMDW")) or name == "README.md"):
                row = siblings[name]
                fetch(f"{url}/resolve/{revision}/{name}", dest / ("upstream-" + name), row.get("size"))
        if license_url:
            fetch(license_url, dest / "MODEL-LICENSE.txt")
        else:
            shutil.copyfile(ROOT / "ThirdParty/SherpaOnnx/LICENSE", dest / "MODEL-LICENSE.txt")
        (dest / "SOURCE.txt").write_text(f"{repo}\nRevision: {revision}\nLicense: {license_name}\n{url}/tree/{revision}\n", encoding="utf-8")
        (dest / "manifest.json").write_text(json.dumps({"revision": revision, "files": entries}, sort_keys=True), encoding="utf-8")
        print("Bundled", repo, revision, sum(row["size"] for row in entries.values()), flush=True)
def verify(apk):
    with zipfile.ZipFile(apk) as package:
        for folder, repo, names, _, _ in MODELS:
            prefix = "assets/bundled-speech/" + folder + "/"
            manifest = json.loads(package.read(prefix + "manifest.json"))
            assert set(manifest["files"]) == set(names)
            assert package.getinfo(prefix + "MODEL-LICENSE.txt").file_size > 0
            assert package.getinfo(prefix + "SOURCE.txt").file_size > 0
            for name, entry in manifest["files"].items():
                info = package.getinfo(prefix + name)
                assert info.file_size == entry["size"], name
                with package.open(info) as file:
                    assert hashlib.file_digest(file, "sha256").hexdigest() == entry["sha256"], name
        print("Verified all 4 speech models in APK:", apk, flush=True)
if __name__ == "__main__":
    assert len(sys.argv) == 3 and sys.argv[1] in ("prepare", "verify")
    (prepare if sys.argv[1] == "prepare" else verify)(Path(sys.argv[2]))

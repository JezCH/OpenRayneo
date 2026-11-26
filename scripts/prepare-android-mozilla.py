#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the pinned, already-patched Mozilla Android source from upstream v0.2.0."""
import argparse
import importlib.util
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import fcntl

SOURCE_URL = "https://github.com/Ngoroth/offline-translator-android/releases/download/v0.2.0/mozilla-native-8eada038-r2.sources.tar.gz"
SOURCE_SHA256 = "befddb1e191089957f4795bdacddc80191b6428ac722ee54986b62b4582f3710"
SOURCE_BYTES = 22653302
SOURCE_NAME = "mozilla-native-8eada038-r2.sources.tar.gz"

def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def source_archive(cache):
    target = cache / SOURCE_NAME
    if not target.is_file():
        partial = target.with_suffix(".partial")
        try:
            with urllib.request.urlopen(SOURCE_URL, timeout=90) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output)
            if partial.stat().st_size != SOURCE_BYTES or sha256(partial) != SOURCE_SHA256:
                raise RuntimeError("Mozilla source archive integrity mismatch")
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    if target.stat().st_size != SOURCE_BYTES or sha256(target) != SOURCE_SHA256:
        raise RuntimeError("Cached Mozilla source archive integrity mismatch")
    return target

def prepare(cache, output, abis):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("Mozilla native build uses the upstream pinned Linux x86_64 toolchain; use Ubuntu/WSL")
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / "openrayneo-build.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        archive = source_archive(cache)
        extracted = cache / ("source-" + SOURCE_SHA256[:16])
        if not (extracted / ".complete").is_file():
            if extracted.exists():
                shutil.rmtree(extracted)
            extracted.mkdir()
            with tarfile.open(archive) as packed:
                packed.extractall(extracted, filter="data")
            (extracted / ".complete").write_text(SOURCE_SHA256)
        buildroot = extracted / "build"
        source = extracted / "source"
        pcre = extracted / "pcre2-10.49"
        spec = importlib.util.spec_from_file_location("pinned_mozilla_prepare", buildroot / "tools/android/prepare-mozilla-native.py")
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        manifest = json.loads((buildroot / "tools/android/mozilla-native-tools.json").read_text())
        if manifest["mozilla_commit"] != "8eada038fd45bbf6fa58a412b22f80c79887fabc":
            raise RuntimeError("Mozilla source revision mismatch")
        ndk, cmake_home, ninja_home = [helper.tool(manifest[k], cache, None) for k in ("ndk", "cmake", "ninja")]
        cmake = cmake_home / "bin/cmake"
        ninja = ninja_home / "ninja"
        if subprocess.check_output([str(cmake), "--version"], text=True).splitlines()[0] != "cmake version 4.2.3":
            raise RuntimeError("Pinned CMake version mismatch")
        if subprocess.check_output([str(ninja), "--version"], text=True).strip() != "1.13.2":
            raise RuntimeError("Pinned Ninja version mismatch")
        if "Pkg.Revision = 29.0.13113456-beta1" not in (ndk / "source.properties").read_text():
            raise RuntimeError("Pinned NDK revision mismatch")
        stage = output.with_name(output.name + ".partial")
        if stage.exists():
            shutil.rmtree(stage)
        stage.mkdir(parents=True)
        for name, content in helper.notices(source, pcre, ndk).items():
            dest = stage / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
        notice = stage / "assets/mozilla-native/THIRD_PARTY.md"
        notice.write_bytes((buildroot / "engine-mozilla/THIRD_PARTY.md").read_bytes())
        (stage / "assets/mozilla-native/OPENRAYNEO_SOURCE.txt").write_text(
            "Mozilla native source: " + SOURCE_URL + "\nSHA-256: " + SOURCE_SHA256 +
            "\nNative source and JNI code reused without changes from upstream v0.2.0."
            "\nMozilla translation models: MPL-2.0, https://github.com/mozilla/translations\n")
        for code in ("en", "zh"):
            dest = stage / f"assets/mozilla/prefixes/{code}.txt"
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / f"inference/3rd_party/ssplit-cpp/nonbreaking_prefixes/nonbreaking_prefix.{code}", dest)
        for abi in abis:
            if abi not in manifest["settings"]["abis"]:
                raise RuntimeError("Mozilla native engine supports only arm64-v8a and x86_64")
            build = cache / f"build-{abi}-{SOURCE_SHA256[:16]}"
            command = [str(cmake), "-S", str(buildroot / "engine-mozilla/src/main/cpp"), "-B", str(build), "-G", "Ninja",
                       f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_BUILD_TYPE=Release", f"-DMOZILLA_SOURCE={source}",
                       f"-DMOZILLA_PCRE_SOURCE={pcre}", f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                       f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_static"]
            subprocess.run(command, check=True)
            subprocess.run([str(cmake), "--build", str(build), "--target", "mozilla_jni", "-j", "2"], check=True)
            dest = stage / f"jni/{abi}/libmozilla_jni.so"
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(build / "jni/libmozilla_jni.so", dest)
            subprocess.run([str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"), "--strip-unneeded", str(dest)], check=True)
            helper.elf_inventory(dest.read_bytes(), abi)
        if output.exists():
            shutil.rmtree(output)
        stage.replace(output)
        print(f"Prepared Mozilla native source {SOURCE_SHA256[:16]} for {','.join(abis)}", flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--abis", required=True)
    args = parser.parse_args()
    prepare(args.cache.resolve(), args.output.resolve(), args.abis.split(","))

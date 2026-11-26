#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lossless APK delta. Copy unchanged raw ZIP member payloads; emit all other signed bytes literally.
No ZIP recompression, no re-signing, bounded streaming memory. Output is byte-identical."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

MAGIC = b"ORDELTA1"
MAX = 1900 * 1024 * 1024
BLOCK = 1024 * 1024

def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()

def transfer(source, dest, count):
    while count:
        block = source.read(min(count, BLOCK))
        if not block:
            raise ValueError("truncated file")
        dest.write(block)
        count -= len(block)

def ranges(path):
    with zipfile.ZipFile(path) as archive, path.open("rb") as source:
        result = {}
        for info in archive.infolist():
            if info.compress_size == 0:
                continue
            source.seek(info.header_offset)
            header = source.read(30)
            if len(header) != 30 or header[:4] != b"PK\x03\x04":
                raise ValueError("invalid ZIP header")
            name, extra = struct.unpack_from("<HH", header, 26)
            offset = info.header_offset + 30 + name + extra
            if offset + info.compress_size > path.stat().st_size or info.filename in result:
                raise ValueError("invalid ZIP ranges")
            result[info.filename] = (offset, info.compress_size, info.CRC, info.compress_type)
        return result

def equal(old, new, old_offset, new_offset, count):
    old.seek(old_offset)
    new.seek(new_offset)
    while count:
        size = min(count, BLOCK)
        a, b = old.read(size), new.read(size)
        if len(a) != size or a != b:
            return False
        count -= size
    return True

def create(base, target, patch):
    base_size, target_size = base.stat().st_size, target.stat().st_size
    if not 0 < base_size <= MAX or not 0 < target_size <= MAX:
        raise ValueError("APK size limit")
    old_ranges, new_ranges = ranges(base), ranges(target)
    copied, commands = 0, 0
    with base.open("rb") as old, target.open("rb") as new, patch.open("wb") as out:
        out.write(MAGIC + struct.pack(">QQ", base_size, target_size))
        pos = 0
        def literal(start, length):
            nonlocal commands
            if length:
                out.write(b"\x01" + struct.pack(">Q", length))
                new.seek(start)
                transfer(new, out, length)
                commands += 1
        for name, (offset, length, crc, method) in sorted(new_ranges.items(), key=lambda item: item[1][0]):
            prior = old_ranges.get(name)
            if offset < pos:
                raise ValueError("overlapping ZIP ranges")
            if length < 64 or prior is None or prior[1:] != (length, crc, method):
                continue
            if not equal(old, new, prior[0], offset, length):
                continue
            literal(pos, offset - pos)
            out.write(b"\x00" + struct.pack(">QQ", prior[0], length))
            commands += 1
            copied += length
            pos = offset + length
        literal(pos, target_size - pos)
        out.write(b"\xff")
        if commands > 200000:
            raise ValueError("too many commands")
    return copied

def restore(base, patch, target):
    with base.open("rb") as old, patch.open("rb") as src, target.open("wb") as out:
        if src.read(8) != MAGIC:
            raise ValueError("invalid patch magic")
        base_size, size = struct.unpack(">QQ", src.read(16))
        if base_size != base.stat().st_size or not 0 < size <= MAX:
            raise ValueError("invalid sizes")
        written = commands = 0
        while True:
            op = src.read(1)
            if op == b"\xff":
                break
            commands += 1
            if commands > 200000 or op not in (b"\x00", b"\x01"):
                raise ValueError("invalid command")
            offset = struct.unpack(">Q", src.read(8))[0] if op == b"\x00" else 0
            length = struct.unpack(">Q", src.read(8))[0]
            if not 0 < length <= size - written:
                raise ValueError("output overflow")
            if op == b"\x00":
                if offset > base_size or length > base_size - offset:
                    raise ValueError("source overflow")
                old.seek(offset)
            transfer(old if op == b"\x00" else src, out, length)
            written += length
        if written != size or src.read(1):
            raise ValueError("incomplete or trailing patch")

if __name__ == "__main__":
    mode, base, target, patch = sys.argv[1:]
    base, target, patch = map(Path, (base, target, patch))
    if mode == "create":
        copied = create(base, target, patch)
        print(json.dumps({"copiedBytes": copied, "patchBytes": patch.stat().st_size, "targetBytes": target.stat().st_size}))
    elif mode == "restore":
        restore(base, patch, target)
    else:
        raise ValueError("unknown mode")

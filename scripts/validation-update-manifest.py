# SPDX-License-Identifier: Apache-2.0
"""Generate the validation update contract from the already verified APK and build-tool reports."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys

directory = Path(sys.argv[1])
apk = directory / "OpenRayneo-validation.apk"
badging = (directory / "openrayneo-badging.txt").read_text()
certs = (directory / "openrayneo-certs.txt").read_text()
package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
abis = re.search(r"^native-code: (.+)$", badging, re.M)
signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certs, re.M)
assert package and sdk and abis and len(signers) == 1, "APK metadata missing"
assert package[1] == "com.openrayneo.android.validation", "Wrong package"
assert int(package[2]) == 1000 + int(os.environ["GITHUB_RUN_NUMBER"]), "Wrong version"
assert abis[1].strip() == "'arm64-v8a'", "Wrong ABI"
signer = signers[0].lower()
assert signer == os.environ["OPENRAYNEO_EXPECTED_SIGNER"], "Wrong certificate"
assert re.fullmatch(r"[0-9a-f]{40}", os.environ["GITHUB_SHA"]), "Invalid commit"
with apk.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
manifest = {
    "schemaVersion": 1, "channel": "validation", "packageName": package[1],
    "versionCode": int(package[2]), "versionName": package[3],
    "minSdk": int(sdk[1]), "abi": "arm64-v8a", "commitSha": os.environ["GITHUB_SHA"],
    "apkAssetName": apk.name, "apkSize": apk.stat().st_size, "apkSha256": digest,
    "signerSha256": signer,
}
(directory / "update-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

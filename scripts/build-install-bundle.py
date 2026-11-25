#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Package the verified APK and matching plugin without exposing signing material."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def assemble(apk, plugin, cert_report, badging, commit, run, fixed, out):
    if not re.fullmatch(r'[0-9a-f]{40}', commit) or run < 1:
        raise ValueError('Invalid build identity')
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != 'com.openrayneo.android' or int(package[2]) != 1000 + run:
        raise ValueError('Unexpected APK identity or versionCode')
    if package[3] != f'1.2.0-wearable.{run}':
        raise ValueError('Unexpected APK versionName')
    signers = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$', cert_report, re.M)
    if not signers:
        raise ValueError('Missing verified signer fingerprint')
    with tarfile.open(plugin, 'r:gz') as archive:
        for name in ('package/package.json', 'package/lib/index.js', 'package/lib/harness.js'):
            member = archive.getmember(name)
            if not member.isfile() or member.size > 1_048_576:
                raise ValueError('Invalid plugin entry: ' + name)
        metadata = json.load(archive.extractfile('package/package.json'))
    version = metadata.get('version', '')
    if metadata.get('name') != 'openrayneo-dsh' or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Unexpected plugin identity')
    if plugin.name != f'openrayneo-dsh-{version}.tgz':
        raise ValueError('Plugin filename/version mismatch')
    manifest = {
        'commit': commit, 'workflowRunNumber': run,
        'apk': {'file': 'app-release.apk', 'package': package[1], 'versionCode': int(package[2]),
                'versionName': package[3], 'sha256': sha256(apk),
                'signerSha256': sorted({value.lower() for value in signers}),
                'fixedSigningKeyConfigured': fixed},
        'plugin': {'file': plugin.name, 'version': version, 'sha256': sha256(plugin)},
        'updateNotice': 'Compare signer fingerprints with the installed APK before updating. A fixed key does not guarantee a match. Never uninstall to bypass a signature mismatch; app data may be lost.',
    }
    out.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(apk, out / manifest['apk']['file'])
    shutil.copyfile(plugin, out / plugin.name)
    (out / 'install-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'plugin', 'cert-report', 'badging', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--run', type=int, required=True)
    parser.add_argument('--fixed-signing-key', choices=('true', 'false'), default='false')
    args = parser.parse_args()
    manifest = assemble(args.apk, args.plugin, args.cert_report.read_text(), args.badging.read_text(),
                        args.commit, args.run, args.fixed_signing_key == 'true', args.out)
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()

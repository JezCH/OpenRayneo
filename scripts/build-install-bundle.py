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


def assemble(apk, plugin, cert_report, badging, commit, run, fixed, out,
             validation_apk=None, validation_cert_report=None, validation_badging=None):
    if not fixed:
        raise ValueError('Persistent signing key required for a distribution bundle')
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
    # Separate package identity protects the existing trial installation and its private data.
    validation_values = (validation_apk, validation_cert_report, validation_badging)
    if any(value is not None for value in validation_values):
        if not all(value is not None for value in validation_values):
            raise ValueError('Incomplete validation APK inputs')
        identity = re.search(
            r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
            validation_badging, re.M)
        if (not identity or identity[1] != 'com.openrayneo.android.validation'
                or int(identity[2]) != 1000 + run
                or identity[3] != f'1.2.0-wearable.{run}-validation'):
            raise ValueError('Unexpected validation APK identity or version')
        validation_signers = re.findall(
            r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
            validation_cert_report, re.M)
        if not validation_signers or sorted({v.lower() for v in validation_signers}) != manifest['apk']['signerSha256']:
            raise ValueError('Validation APK signer differs from release APK')
        manifest['validationApk'] = {
            'file': 'openrayneo-validation.apk', 'package': identity[1],
            'versionCode': int(identity[2]), 'versionName': identity[3],
            'sha256': sha256(validation_apk),
            'signerSha256': sorted({v.lower() for v in validation_signers}),
            'isolatedInstall': True,
            'notice': 'Installs beside com.openrayneo.android; does not share records or settings. Stop the other bridge before connecting glasses.',
        }
    out.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(apk, out / manifest['apk']['file'])
    if 'validationApk' in manifest:
        shutil.copyfile(validation_apk, out / manifest['validationApk']['file'])
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
    for name in ('validation-apk', 'validation-cert-report', 'validation-badging'):
        parser.add_argument('--' + name, type=Path)
    args = parser.parse_args()
    manifest = assemble(args.apk, args.plugin, args.cert_report.read_text(), args.badging.read_text(),
                        args.commit, args.run, args.fixed_signing_key == 'true', args.out,
                        validation_apk=args.validation_apk,
                        validation_cert_report=args.validation_cert_report.read_text() if args.validation_cert_report else None,
                        validation_badging=args.validation_badging.read_text() if args.validation_badging else None)
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()

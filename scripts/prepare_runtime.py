#!/usr/bin/env python3
"""Prepare the pinned Linux/arm64 guest and hash-locked wheels on a Mac."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / 'android' / 'repo'
MANIFEST = 'sha256:aed314f1f0c7e988b7aee5d889414ec977430bad37770966c52aff5366a13a06'
API = 'https://registry-1.docker.io/v2/library/python'
MEDIA = 'application/vnd.oci.image.manifest.v1+json'


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def tar_add(tar, name, data):
    item = tarfile.TarInfo(name)
    item.size, item.mode, item.mtime, item.uid, item.gid = len(data), 0o644, 0, 0, 0
    item.uname = item.gname = ''
    tar.addfile(item, io.BytesIO(data))


def prepare_image(offline):
    cache = ROOT / 'images' / 'oci-python-arm64' / 'blobs' / 'sha256'
    cache.mkdir(parents=True, exist_ok=True)
    token = None
    def fetch(path, expected, size=None):
        nonlocal token
        target = cache / expected.removeprefix('sha256:')
        if target.exists():
            data = target.read_bytes()
            if digest(data) == expected and (size is None or len(data) == size):
                return data
        if offline:
            raise ValueError('Missing or invalid OCI cache blob: ' + expected)
        if token is None:
            url = 'https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Fpython%3Apull'
            with urllib.request.urlopen(url, timeout=90) as response:
                token = json.load(response)['token']
        req = urllib.request.Request(API + path, headers={'Authorization': 'Bearer ' + token, 'Accept': MEDIA})
        with urllib.request.urlopen(req, timeout=90) as response:
            data = response.read()
        if digest(data) != expected or (size is not None and len(data) != size):
            raise ValueError('Downloaded OCI content failed verification')
        target.write_bytes(data)
        return data
    manifest = fetch('/manifests/' + MANIFEST, MANIFEST)
    doc = json.loads(manifest)
    descriptors = [doc['config'], *doc['layers']]
    blobs = [(MANIFEST, manifest)]
    for item in descriptors:
        expected = item['digest']
        if not expected.startswith('sha256:') or len(expected) != 71:
            raise ValueError('Unsupported OCI digest')
        blobs.append((expected, fetch('/blobs/' + expected, expected, item['size'])))
    descriptor = {'mediaType': MEDIA, 'digest': MANIFEST, 'size': len(manifest),
                  'platform': {'architecture': 'arm64', 'os': 'linux', 'variant': 'v8'}}
    index = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json', 'manifests': [descriptor]}
    image = ROOT / 'images' / 'python-3.13-slim-bookworm-linux-arm64.oci.tar'
    with tarfile.open(image, 'w') as tar:
        tar_add(tar, 'oci-layout', b'{"imageLayoutVersion":"1.0.0"}\n')
        tar_add(tar, 'index.json', (json.dumps(index, separators=(',', ':')) + '\n').encode())
        for key, data in blobs:
            tar_add(tar, 'blobs/sha256/' + key[7:], data)
    return image


def prepare_wheels(offline):
    cache = ROOT / 'wheels'
    requirements = cache / 'requirements.txt'
    with tempfile.TemporaryDirectory() as temp:
        cmd = [sys.executable, '-m', 'pip', 'download', '--dest', temp, '--require-hashes',
               '--only-binary=:all:', '--no-deps', '--implementation', 'cp', '--python-version', '3.13',
               '--abi', 'cp313', '--abi', 'abi3', '--abi', 'none',
               '--platform', 'manylinux_2_34_aarch64', '--platform', 'manylinux_2_28_aarch64',
               '--platform', 'manylinux_2_17_aarch64', '--platform', 'manylinux2014_aarch64',
               '-r', str(requirements)]
        if offline:
            cmd += ['--no-index', '--find-links', str(cache)]
        subprocess.run(cmd, check=True)
        bundle = ROOT / 'wheel-bundle.tar'
        with tarfile.open(bundle, 'w') as tar:
            tar_add(tar, 'wheels/requirements.txt', requirements.read_bytes())
            for wheel in sorted(Path(temp).glob('*.whl')):
                tar_add(tar, 'wheels/' + wheel.name, wheel.read_bytes())
    return bundle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true', help='use existing verified OCI and wheel caches')
    args = parser.parse_args()
    image = prepare_image(args.offline)
    bundle = prepare_wheels(args.offline)
    entries = [(image, 'python-arm64.oci.tar'), (bundle, 'wheel-bundle.tar')]
    (ROOT / 'runtime-sha256.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + name + '\n' for p, name in entries))
    print('Prepared runtime files in', ROOT)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Build the native helper with JDK 17+ and an Android SDK (API 36)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BUILD = HERE / 'build'
PRIVATE = HERE / 'private'


def configuration():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ROOT / 'tools/android-sdk')
    parser.add_argument('--build-tools', help='SDK build-tools version, e.g. 36.0.0')
    parser.add_argument('--unsigned', action='store_true', help='Build without creating or accessing a signing key')
    args = parser.parse_args()
    sdk = args.sdk.expanduser().resolve()
    android = sdk / 'platforms/android-36/android.jar'
    if not android.is_file():
        android = sdk / 'android-36/android.jar'  # Existing workstation SDK layout.
    if args.build_tools:
        tools = sdk / 'build-tools' / args.build_tools
    else:
        choices = [p for p in (sdk / 'build-tools').glob('*') if p.is_dir() and p.name.replace('.', '').isdigit()]
        tools = max(choices, key=lambda p: tuple(map(int, p.name.split('.')))) if choices else sdk / 'android-16'
    configured = os.environ.get('JAVA_HOME')
    if configured:
        java = Path(configured) / 'bin'
    elif Path('/usr/libexec/java_home').is_file():
        java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17+'], text=True).strip()) / 'bin'
    else:
        javac = shutil.which('javac')
        if not javac:
            parser.error('Install JDK 17 or later, or set JAVA_HOME.')
        java = Path(javac).resolve().parent
    for path in (android, tools / 'aapt2', tools / 'zipalign', tools / 'lib/d8.jar', tools / 'lib/apksigner.jar', java / 'javac'):
        if not path.is_file():
            parser.error(f'Missing build dependency: {path}')
    return args, android, tools, java


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def check_signing_pair(keystore, password):
    if keystore.exists() != password.exists():
        raise RuntimeError('Incomplete signing material; restore the original key and password before building. Neither file was changed.')


def main():
    args, android, tools, java = configuration()
    os.umask(0o077)
    BUILD.mkdir(exist_ok=True)
    (ROOT / 'android/apks').mkdir(exist_ok=True)
    (ROOT / 'results/android').mkdir(parents=True, exist_ok=True)
    classes = BUILD / 'classes'
    classes.mkdir(exist_ok=True)
    for old in classes.rglob('*.class'):
        old.unlink()
    run(java / 'javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8',
        '-classpath', android, '-d', classes, *sorted((HERE / 'src').rglob('*.java')))
    jar = BUILD / 'classes.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes))
    # Invoke jars with the selected JDK without changing system-wide Java settings.
    run(java / 'java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '31', '--lib', android, '--output', BUILD, jar)
    unsigned = BUILD / 'unsigned.apk'
    resources = BUILD / 'resources.zip'
    resources.unlink(missing_ok=True)
    run(tools / 'aapt2', 'compile', '--dir', HERE / 'res', '-o', resources)
    run(tools / 'aapt2', 'link', '-o', unsigned, '--manifest', HERE / 'AndroidManifest.xml', '-I', android, resources)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
        archive.write(BUILD / 'classes.dex', 'classes.dex')
    aligned = BUILD / 'aligned.apk'
    run(tools / 'zipalign', '-f', '4', unsigned, aligned)
    namespace = '{http://schemas.android.com/apk/res/android}'
    declaration = ET.parse(HERE / 'AndroidManifest.xml').getroot()
    version = declaration.attrib[namespace + 'versionName']
    if args.unsigned:
        apk = ROOT / 'android/apks' / f'location-bridge-source-{version}-unsigned.apk'
        shutil.copyfile(aligned, apk)
        print('Unsigned APK:', apk)
        return
    PRIVATE.mkdir(exist_ok=True, mode=0o700)
    keystore = PRIVATE / 'local-signing.p12'
    password = PRIVATE / 'keystore-password.txt'
    check_signing_pair(keystore, password)
    if not keystore.exists():
        password.write_text(secrets.token_urlsafe(32))
        run(java / 'keytool', '-genkeypair', '-keystore', keystore,
            '-storetype', 'PKCS12', '-storepass:file', password, '-alias', 'location-helper',
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650',
            '-dname', 'CN=Location Bridge Local Build', '-noprompt')
    if not password.is_file():
        raise RuntimeError('Signing password is missing; keep the original private folder for updates.')
    apk = ROOT / 'android/apks' / f'location-bridge-source-{version}.apk'
    run(java / 'java', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', keystore,
        '--ks-key-alias', 'location-helper', '--ks-pass', 'file:' + str(password), '--out', apk, aligned)
    run(java / 'java', '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
    manifest = {'apk': str(apk.relative_to(ROOT)), 'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
                'package':declaration.attrib['package'], 'version':version,
                'version_code':int(declaration.attrib[namespace + 'versionCode']), 'min_sdk':31, 'target_sdk':36,
                'signing':'local self-signed; preserve private directory for updates',
                'runtime_dependencies':'Android platform APIs only; no Google Play Services or AMap SDK',
                'device_validation':'pending'}
    (ROOT / 'results/android/helper-apk-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('APK:', apk)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Assemble the modified JAR with checksum-verified Windows launcher and full JRE.

For a native, wholly rebuilt launcher, run `gradlew.bat packageWindows` on Windows.
This assembly never reads or includes application.yml, databases or financial files.
"""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import zipfile

LAUNCHER_SHA256 = '971fb5a9b69ac99b286f8443a15cf71b99f4b86c2845eac8acc11786db148503'
JRE_SHA256 = 'da78d54a2fcdbba1e92d5e3f07dd30236f96297891c4a21a781c3f38f5c0beae'


def checked(path, digest):
    with path.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != digest:
        raise ValueError(f'Official archive checksum mismatch: {path.name}')
    archive = zipfile.ZipFile(path)
    if archive.testzip() is not None:
        raise ValueError(f'Corrupt archive: {path.name}')
    for name in archive.namelist():
        if PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
            raise ValueError('Unsafe archive path')
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, required=True)
    parser.add_argument('--jre', type=Path, required=True)
    parser.add_argument('--launcher-exe', type=Path, help='Optional verified icon-patched launcher')
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    with checked(args.launcher, LAUNCHER_SHA256) as original, checked(args.jre, JRE_SHA256) as runtime:
        root = next(n.split('/')[0] for n in runtime.namelist() if n.endswith('/release'))
        release = runtime.read(root + '/release').decode()
        if 'jdk.httpserver' not in release or 'OS_NAME="Windows"' not in release:
            raise ValueError('Full Windows runtime with jdk.httpserver is required')
        with zipfile.ZipFile(args.output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as out:
            for item in original.infolist():
                name = item.filename
                if item.is_dir() or name.startswith('ChurchSteward/runtime/') or name.endswith('.jar') or name.endswith('.cfg'):
                    continue
                data=args.launcher_exe.read_bytes() if name=='ChurchSteward/ChurchSteward.exe' and args.launcher_exe else original.read(name)
                out.writestr(name, data)
            for item in runtime.infolist():
                if item.is_dir():
                    continue
                relative = item.filename.removeprefix(root + '/')
                out.writestr('ChurchSteward/runtime/' + relative, runtime.read(item.filename))
            out.write(args.jar, 'ChurchSteward/app/church-steward-1.4.1.jar')
            out.writestr('ChurchSteward/app/ChurchSteward.cfg', '[Application]\r\napp.mainjar=$APPDIR\\church-steward-1.4.1.jar\r\n\r\n[JavaOptions]\r\njava-options=-Djpackage.app-version=1.4.1\r\n')
            out.writestr('ChurchSteward/app/saichurch-1.4.1.txt', 'Sai Church customized application 1.4.1\n')
            for file in ['Start-Windows.cmd', 'README.md', 'LICENSE', 'LICENSE-ko', 'docs/saichurch-1.3.md', 'docs/updates-1.4.1.md']:
                target = Path(file).name
                out.write(repo / file, 'ChurchSteward/' + target)
    with zipfile.ZipFile(args.output) as result:
        assert result.testzip() is None
        assert result.read('ChurchSteward/ChurchSteward.exe')[:2] == b'MZ'
        assert result.read('ChurchSteward/runtime/bin/server/jvm.dll')[:2] == b'MZ'
        assert not any('/data/' in n or n.endswith('application.yml') for n in result.namelist())
    with args.output.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    args.output.with_suffix(args.output.suffix + '.sha256').write_text(digest + '  ' + args.output.name + '\n')
    print(f'{args.output}: {args.output.stat().st_size} bytes; SHA256={digest}')


if __name__ == '__main__':
    main()

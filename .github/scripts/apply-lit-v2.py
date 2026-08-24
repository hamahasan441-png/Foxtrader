import os, shutil, stat, zipfile
from pathlib import Path, PurePosixPath

repo = Path.cwd()
archive = repo / 'Foxtrader-LitAdventure-v2.zip'
if not archive.is_file():
    raise SystemExit('Archive missing; nothing to apply')
out = Path(os.environ['RUNNER_TEMP']) / 'litv2-apply'
out.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    for info in z.infolist():
        p = PurePosixPath(info.filename)
        mode = (info.external_attr >> 16) & 0o170000
        if p.is_absolute() or '..' in p.parts or mode == stat.S_IFLNK:
            raise SystemExit(f'Unsafe archive member: {info.filename}')
    z.extractall(out)

roots = [p.parent for p in out.rglob('settings.gradle.kts') if (p.parent / 'app').is_dir()]
if not roots:
    raise SystemExit('No Android project root in archive')
roots.sort(key=lambda p: (len(p.relative_to(out).parts), str(p)))
srcroot = roots[0]

scopes = [
    Path('app/src/main/java/com/foxtrader/domain/trading/lit'),
    Path('app/src/main/java/com/foxtrader/domain/trading/litx'),
    Path('app/src/main/java/com/foxtrader/domain/model/litx'),
    Path('app/src/main/java/com/foxtrader/data/local/litx'),
    Path('app/src/main/java/com/foxtrader/data/repository/litx'),
    Path('app/src/test/java/com/foxtrader/domain/trading/lit'),
    Path('app/src/test/java/com/foxtrader/domain/trading/litx'),
    Path('app/src/test/java/com/foxtrader/domain/model/litx'),
    Path('app/src/test/java/com/foxtrader/data/local/litx'),
    Path('app/src/test/java/com/foxtrader/data/repository/litx'),
]

touched = 0
for rel in scopes:
    src = srcroot / rel
    dst = repo / rel
    if src.exists():
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        count = sum(1 for p in src.rglob('*') if p.is_file())
        touched += count
        print(f'REPLACED {rel} ({count} files)')
    elif dst.exists():
        shutil.rmtree(dst)
        print(f'DELETED stale scope {rel}')

if touched == 0:
    raise SystemExit('No LiT/LiTX files found in archive; refusing empty replacement')
print(f'Total archive LiT/LiTX files copied: {touched}')

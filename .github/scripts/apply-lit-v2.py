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

# Claude's v2 archive uses the app-layer LiTX package layout below. Replace only
# these superseded LiT Adventure packages so newer provider/RSI/chart work on
# current main is preserved.
scopes = [
    Path('app/src/main/java/com/foxtrader/app/domain/usecase/litx'),
    Path('app/src/main/java/com/foxtrader/app/feature/litx'),
    Path('app/src/test/java/com/foxtrader/app/domain/usecase/litx'),
    Path('app/src/test/java/com/foxtrader/app/feature/litx'),
]

def resolve_scope(rel: Path):
    suffix = rel.as_posix()
    candidates = [p for p in out.rglob(rel.name) if p.is_dir() and p.as_posix().endswith(suffix)]
    if not candidates:
        return None
    candidates.sort(key=lambda p: (-sum(1 for x in p.rglob('*') if x.is_file()), len(p.parts), str(p)))
    return candidates[0]

touched = 0
replaced_scopes = 0
for rel in scopes:
    src = resolve_scope(rel)
    dst = repo / rel
    if src is None:
        print(f'ARCHIVE SCOPE ABSENT, PRESERVED CURRENT: {rel}')
        continue
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    count = sum(1 for p in src.rglob('*') if p.is_file())
    touched += count
    replaced_scopes += 1
    print(f'REPLACED {rel} FROM {src.relative_to(out)} ({count} files)')

if touched == 0 or replaced_scopes == 0:
    discovered = [p.relative_to(out).as_posix() for p in out.rglob('*') if p.is_file() and '/litx/' in p.as_posix().lower()]
    print('DISCOVERED LITX FILES:')
    for p in discovered[:200]:
        print(p)
    raise SystemExit('No LiT Adventure v2 scoped files found in archive; refusing empty replacement')
print(f'Total LiT Adventure v2 files copied: {touched} across {replaced_scopes} scopes')

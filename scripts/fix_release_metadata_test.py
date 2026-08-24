from pathlib import Path
p = Path('app/src/test/java/com/newoether/agora/WorkbenchReleaseMetadataTest.kt')
t = p.read_text(encoding='utf-8')
old = 'val gradle = File("app/build.gradle.kts").readText()'
new = 'val gradle = File("build.gradle.kts").readText()'
if t.count(old) != 1:
    raise SystemExit(f'expected one path match, got {t.count(old)}')
p.write_text(t.replace(old, new), encoding='utf-8')

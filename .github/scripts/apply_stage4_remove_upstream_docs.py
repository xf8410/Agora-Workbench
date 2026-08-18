#!/usr/bin/env python3
"""Remove upstream Agora documentation FABs from Workbench settings pages.

The old DataStore key is deliberately left intact for compatibility. This script only removes
user-facing controls, FAB calls, and their artificial bottom spacing, then deletes the component
once no Kotlin source references it.
"""
from pathlib import Path
import re

ROOT = Path("app/src/main/java/com/newoether/agora")
SETTINGS = ROOT / "ui/settings"

for path in SETTINGS.rglob("*.kt"):
    if path.name == "DocumentationFab.kt":
        continue
    original = path.read_text(encoding="utf-8")
    text = original

    # Per-page state that existed only to decide whether the upstream-doc FAB was visible.
    text = re.sub(
        r"(?m)^\s*val showDocFab by viewModel\.settings\.showDocumentationFab\.collectAsState\(\)\s*\n",
        "",
        text,
    )

    # Named scaffold argument. A trailing comma on the previous named argument is valid Kotlin.
    text = re.sub(
        r'(?m)^\s*floatingActionButton\s*=\s*\{\s*if\s*\(showDocFab\)\s*DocumentationFab\("[^"]+"\)\s*}\s*,?\s*\n',
        "",
        text,
    )

    # Extra space that only prevented page content from sitting behind the FAB.
    text = re.sub(
        r"(?m)^\s*if\s*\(showDocFab\)\s*\{\s*Spacer\(modifier\s*=\s*Modifier\.height\(80\.dp\)\)\s*}\s*\n",
        "",
        text,
    )

    # Appearance-page switch item, if present in a source revision. Match only an item whose
    # entire behavior is the showDocumentationFab setting; unrelated interface settings remain.
    text = re.sub(
        r"(?ms)^\s*add\s*\{\s*SettingsItem\(.*?showDocumentationFab.*?\n\s*}\s*\n",
        "",
        text,
    )

    if text != original:
        path.write_text(text, encoding="utf-8")

remaining = []
for path in SETTINGS.rglob("*.kt"):
    if path.name == "DocumentationFab.kt":
        continue
    source = path.read_text(encoding="utf-8")
    if "DocumentationFab(" in source or "showDocumentationFab" in source or "showDocFab" in source:
        remaining.append(str(path))

if remaining:
    raise SystemExit("Documentation UI references remain:\n" + "\n".join(remaining))

component = SETTINGS / "DocumentationFab.kt"
if component.exists():
    component.unlink()

#!/usr/bin/env python3
"""Remove upstream Agora documentation FABs from Workbench settings pages.

Keep the legacy DataStore preference for backup compatibility, but remove every user-facing FAB,
its per-page state collector, and FAB-only spacing. The transformation is idempotent and supports
both one-line and multiline scaffold arguments used across settings pages.
"""
from pathlib import Path
import re

SETTINGS = Path("app/src/main/java/com/newoether/agora/ui/settings")
component = SETTINGS / "DocumentationFab.kt"

for path in SETTINGS.rglob("*.kt"):
    if path == component:
        continue
    original = path.read_text(encoding="utf-8")
    text = original

    # State aliases used solely by the documentation FAB. Names vary slightly by page.
    text = re.sub(
        r"(?m)^\s*val\s+\w*[Dd]oc\w*\s+by\s+viewModel\.settings\.showDocumentationFab\.collectAsState\(\)\s*\n",
        "",
        text,
    )

    # Common single-line named scaffold argument.
    text = re.sub(
        r'(?m)^\s*floatingActionButton\s*=\s*\{[^\n]*DocumentationFab\("[^"]+"\)[^\n]*}\s*,?\s*\n',
        "",
        text,
    )
    # Multiline named argument, bounded by the next scaffold argument/content lambda.
    text = re.sub(
        r'(?ms)^\s*floatingActionButton\s*=\s*\{.*?DocumentationFab\("[^"]+"\).*?^\s*}\s*,?\s*\n',
        "",
        text,
    )

    # FAB-only trailing clearance. Accept aliases rather than relying on one exact state name.
    text = re.sub(
        r"(?m)^\s*if\s*\(\w*[Dd]oc\w*\)\s*\{\s*Spacer\(modifier\s*=\s*Modifier\.height\(80\.dp\)\)\s*}\s*\n",
        "",
        text,
    )

    if text != original:
        path.write_text(text, encoding="utf-8")

# A call without its component would be a compile error; provide actionable file names if a new
# page shape appears instead of silently deleting the declaration.
callers = []
for path in SETTINGS.rglob("*.kt"):
    if path != component and "DocumentationFab(" in path.read_text(encoding="utf-8"):
        callers.append(str(path))
if callers:
    raise SystemExit("DocumentationFab calls remain:\n" + "\n".join(callers))

if component.exists():
    component.unlink()

from pathlib import Path

cn = Path("README_CN.md").read_text()
root_path = Path("README.md")
root = root_path.read_text()
marker = "\n---\n\n<div align=\"center\">"
if marker not in cn:
    raise SystemExit("README_CN verified intro boundary not found")
intro = cn.split(marker, 1)[0].rstrip() + "\n\n---\n\n"
if root.startswith("# Agora Workbench 已验证补丁与功能说明"):
    raise SystemExit(0)
root_path.write_text(intro + root)

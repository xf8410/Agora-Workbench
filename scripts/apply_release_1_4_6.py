from pathlib import Path

build = Path("app/build.gradle.kts")
text = build.read_text()
old = '''        versionCode = 33
        versionName = "1.4.5-workbench"
'''
new = '''        versionCode = 34
        versionName = "1.4.6-workbench"
'''
if old in text:
    build.write_text(text.replace(old, new, 1))
elif new not in text:
    raise SystemExit("version block not found")

about = Path("app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt")
text = about.read_text()
replacements = {
    'https://github.com/newo-ether/Agora"': 'https://github.com/xf8410/Agora-Workbench"',
    'https://github.com/newo-ether/Agora/issues"': 'https://github.com/xf8410/Agora-Workbench/issues"',
    'https://github.com/newo-ether/Agora/pulls"': 'https://github.com/xf8410/Agora-Workbench/pulls"',
    'https://github.com/newo-ether/Agora/blob/master/PRIVACY.md"': 'https://github.com/xf8410/Agora-Workbench/blob/main/PRIVACY.md"',
}
for old_url, new_url in replacements.items():
    text = text.replace(old_url, new_url)
about.write_text(text)

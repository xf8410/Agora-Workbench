from pathlib import Path

path = Path("app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt")
text = path.read_text()
start_marker = "            // -- Rating Section (title + card as one unit so the title stays tight to the card) --\n"
end_marker = "            }\n            }\n    }\n}\n"
if start_marker in text:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    text = text[:start] + "            }\n    }\n}\n"
    path.write_text(text)
elif "RatingForm()" in text:
    raise RuntimeError("rating section marker mismatch")

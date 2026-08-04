from pathlib import Path

path = Path("app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt")
text = path.read_text()
old = """                        visible = !isStreaming,
                        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
"""
new = """                        visible = shouldShowAssistantActions(isStreaming, message.text),
                        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
"""
if old in text:
    path.write_text(text.replace(old, new, 1))
elif new not in text:
    raise SystemExit("assistant action visibility target not found")

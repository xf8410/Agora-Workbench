from pathlib import Path

path = Path('app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt')
text = path.read_text()
old = 'additionalToolProviders = listOf(automationToolProvider),'
new = '''additionalToolProviders = listOf(
                automationToolProvider,
                com.newoether.agora.tool.GitHubCloneToolProvider(appContext, sandboxFactory),
            ),'''
if new in text:
    raise SystemExit('already registered')
if text.count(old) != 1:
    raise SystemExit(f'expected one registration anchor, found {text.count(old)}')
path.write_text(text.replace(old, new))

from pathlib import Path

path = Path("app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt")
text = path.read_text()
old = '''    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app).also { provider ->
        provider.confirm = { summary -> onConfirmShellCommand?.invoke("GitHub", summary) ?: false }
    }
    private val umaToolProvider = com.newoether.agora.tool.UmaToolProvider()'''
new = '''    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app).also { provider ->
        provider.confirm = { summary -> onConfirmShellCommand?.invoke("GitHub", summary) ?: false }
    }
    private val githubWatchToolProvider = com.newoether.agora.tool.GitHubWatchToolProvider(app)
    private val umaToolProvider = com.newoether.agora.tool.UmaToolProvider()'''
if text.count(old) != 1:
    raise RuntimeError("GitHub provider declaration marker mismatch")
text = text.replace(old, new, 1)
old = '''        memoryToolProvider, webSearchToolProvider, ragToolProvider, imageGenToolProvider,
        githubToolProvider, umaToolProvider, shellToolProvider'''
new = '''        memoryToolProvider, webSearchToolProvider, ragToolProvider, imageGenToolProvider,
        githubToolProvider, githubWatchToolProvider, umaToolProvider, shellToolProvider'''
if text.count(old) != 1:
    raise RuntimeError("GitHub provider list marker mismatch")
path.write_text(text.replace(old, new, 1))
Path(__file__).unlink()

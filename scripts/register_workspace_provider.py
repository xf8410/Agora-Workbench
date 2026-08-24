from pathlib import Path

path = Path("app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt")
text = path.read_text(encoding="utf-8")
old = """    private val githubActionsLogToolProvider = com.newoether.agora.tool.GitHubActionsLogToolProvider(app)\n    private val githubPullRequestToolProvider"""
new = """    private val githubActionsLogToolProvider = com.newoether.agora.tool.GitHubActionsLogToolProvider(app)\n    private val githubWorkspaceToolProvider = com.newoether.agora.tool.GitHubWorkspaceToolProvider(app)\n    private val githubPullRequestToolProvider"""
if text.count(old) != 1:
    raise SystemExit(f"provider declaration match: {text.count(old)}")
text = text.replace(old, new)
old = """        githubToolProvider, githubWatchToolProvider, githubActionsLogToolProvider,\n        githubPullRequestToolProvider,"""
new = """        githubToolProvider, githubWatchToolProvider, githubActionsLogToolProvider,\n        githubWorkspaceToolProvider, githubPullRequestToolProvider,"""
if text.count(old) != 1:
    raise SystemExit(f"provider list match: {text.count(old)}")
path.write_text(text.replace(old, new), encoding="utf-8")

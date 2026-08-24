from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    p = Path(path); text = p.read_text(encoding="utf-8")
    if text.count(old) != 1: raise SystemExit(f"{path}: {text.count(old)} matches")
    p.write_text(text.replace(old, new), encoding="utf-8")

replace(
    "app/src/main/java/com/newoether/agora/di/AppContainer.kt",
    """    val taskManager: TaskManager by lazy {""",
    """    val workspaceAgentRunner: com.newoether.agora.workspace.WorkspaceAgentRunner by lazy {\n        com.newoether.agora.workspace.WorkspaceAgentRunner(\n            conversations = conversationRepository,\n            engine = taskExecutionEngine,\n            scope = appScope,\n        )\n    }\n\n    val taskManager: TaskManager by lazy {""",
)
replace(
    "app/src/main/java/com/newoether/agora/MainActivity.kt",
    """                com.newoether.agora.ui.workspace.GitHubWorkspaceScreen(\n                    onBack = { showWorkspace = false }\n                )""",
    """                com.newoether.agora.ui.workspace.GitHubWorkspaceScreen(\n                    runner = (appContext as AgoraApplication).container.workspaceAgentRunner,\n                    onBack = { showWorkspace = false }\n                )""",
)

p = Path("app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt")
text = p.read_text(encoding="utf-8")
old = """        // A headless worker cannot ask the user to approve a remote mutation. Fail closed.\n        it.onConfirmShellCommand = { _, _ -> false }"""
new = """        // Remote shell remains unavailable to headless runs. Workspace GitHub mutations use\n        // GitHubMutationConfirmation and therefore still fail closed unless the foreground user\n        // approves the exact repository/ref/SHA dialog.\n        it.onConfirmShellCommand = { _, _ -> false }\n        it.onConfirmGitHubAction = { repository, summary ->\n            com.newoether.agora.viewmodel.GitHubMutationConfirmation.confirm(\n                \"$repository\\n$summary\"\n            )\n        }"""
if text.count(old) != 1: raise SystemExit(f"TaskExecutionEngine: {text.count(old)} matches")
p.write_text(text.replace(old, new), encoding="utf-8")

from pathlib import Path


def patch(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


patch(
    "app/src/main/java/com/newoether/agora/ui/chat/ChatDrawerContent.kt",
    """    onOpenSettings: () -> Unit,\n    onOpenTasks: () -> Unit,\n    onRequestRename: (String, String) -> Unit,""",
    """    onOpenSettings: () -> Unit,\n    onOpenTasks: () -> Unit,\n    onOpenWorkspace: () -> Unit,\n    onRequestRename: (String, String) -> Unit,""",
)
patch(
    "app/src/main/java/com/newoether/agora/ui/chat/ChatDrawerContent.kt",
    """            if (!search.isActive) {\n                FilledTonalButton(\n                    onClick = {\n                        haptics.action()\n                        focusManager.clearFocus()\n                        onOpenTasks()""",
    """            if (!search.isActive) {\n                FilledTonalButton(\n                    onClick = {\n                        haptics.action()\n                        focusManager.clearFocus()\n                        onOpenWorkspace()\n                        scope.launch { drawerState.close() }\n                    },\n                    modifier = Modifier.fillMaxWidth().height(42.dp),\n                    shape = CircleShape\n                ) {\n                    Icon(Icons.Default.Code, null, modifier = Modifier.size(20.dp))\n                    Spacer(modifier = Modifier.width(8.dp))\n                    Text(\"工作区\", style = ChatType.drawerButton)\n                }\n\n                Spacer(modifier = Modifier.height(10.dp))\n\n                FilledTonalButton(\n                    onClick = {\n                        haptics.action()\n                        focusManager.clearFocus()\n                        onOpenTasks()""",
)

patch(
    "app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt",
    """    onOpenSettings: () -> Unit,\n    onOpenTasks: (String?) -> Unit = {},\n    onMediaClick:""",
    """    onOpenSettings: () -> Unit,\n    onOpenTasks: (String?) -> Unit = {},\n    onOpenWorkspace: () -> Unit = {},\n    onMediaClick:""",
)
patch(
    "app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt",
    """                onOpenSettings = onOpenSettings,\n                onOpenTasks = { onOpenTasks(null) },\n                onRequestRename""",
    """                onOpenSettings = onOpenSettings,\n                onOpenTasks = { onOpenTasks(null) },\n                onOpenWorkspace = onOpenWorkspace,\n                onRequestRename""",
)

patch(
    "app/src/main/java/com/newoether/agora/MainActivity.kt",
    """    var showSettings by rememberSaveable { mutableStateOf(false) }\n    var showTasks by rememberSaveable { mutableStateOf(false) }\n    var taskToOpen""",
    """    var showSettings by rememberSaveable { mutableStateOf(false) }\n    var showTasks by rememberSaveable { mutableStateOf(false) }\n    var showWorkspace by rememberSaveable { mutableStateOf(false) }\n    var taskToOpen""",
)
patch(
    "app/src/main/java/com/newoether/agora/MainActivity.kt",
    """                showSettings = false\n                showTasks = false\n                taskToOpen = null""",
    """                showSettings = false\n                showTasks = false\n                showWorkspace = false\n                taskToOpen = null""",
)
patch(
    "app/src/main/java/com/newoether/agora/MainActivity.kt",
    """        if (showSettings || fullScreenMediaUrls != null) navBarPadding else chatSnackbarOffset""",
    """        if (showSettings || showTasks || showWorkspace || fullScreenMediaUrls != null) navBarPadding else chatSnackbarOffset""",
)
patch(
    "app/src/main/java/com/newoether/agora/MainActivity.kt",
    """                onOpenTasks = { taskId ->\n                    taskToOpen = taskId\n                    showTasks = true\n                },\n                onMediaClick""",
    """                onOpenTasks = { taskId ->\n                    taskToOpen = taskId\n                    showTasks = true\n                },\n                onOpenWorkspace = {\n                    showWorkspace = true\n                },\n                onMediaClick""",
)
patch(
    "app/src/main/java/com/newoether/agora/MainActivity.kt",
    """            // Full screen image preview\n            AnimatedVisibility(""",
    """            SettingsOverlayHost(\n                visible = showWorkspace,\n                onDismiss = { showWorkspace = false }\n            ) {\n                com.newoether.agora.ui.workspace.GitHubWorkspaceScreen(\n                    onBack = { showWorkspace = false }\n                )\n            }\n\n            // Full screen image preview\n            AnimatedVisibility(""",
)

print("workspace navigation patched")

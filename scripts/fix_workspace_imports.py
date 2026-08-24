from pathlib import Path


def add_after(path: str, anchor: str, addition: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if addition.strip() in text:
        return
    if text.count(anchor) != 1:
        raise SystemExit(f"{path}: anchor count={text.count(anchor)}")
    file.write_text(text.replace(anchor, anchor + addition), encoding="utf-8")


add_after(
    "app/src/main/java/com/newoether/agora/ui/chat/ChatDrawerContent.kt",
    "import androidx.compose.material.icons.filled.Add\n",
    "import androidx.compose.material.icons.filled.Code\n",
)
add_after(
    "app/src/main/java/com/newoether/agora/ui/workspace/GitHubWorkspaceScreen.kt",
    "import androidx.compose.foundation.layout.size\n",
    "import androidx.compose.foundation.layout.weight\n",
)
print("workspace imports fixed")

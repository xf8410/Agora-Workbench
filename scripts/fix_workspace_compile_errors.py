from pathlib import Path

# Kotlin supports nested block comments: a literal workbench/* inside KDoc opens a nested comment.
p = Path('app/src/main/java/com/newoether/agora/workspace/WorkspaceAgentRunner.kt')
t = p.read_text(encoding='utf-8')
t = t.replace('Intermediate commits and CI must remain on workbench/* branches.',
              'Intermediate commits and CI must remain on branches whose names start with workbench/.')
p.write_text(t, encoding='utf-8')

# Avoid importing Compose's internal parent-data weight symbol; RowScope/ColumnScope supplies it.
p = Path('app/src/main/java/com/newoether/agora/ui/workspace/GitHubWorkspaceScreen.kt')
t = p.read_text(encoding='utf-8')
t = t.replace('import androidx.compose.foundation.layout.weight\n', '')
p.write_text(t, encoding='utf-8')

# Use named ToolFunction arguments so constructor ordering cannot be confused.
p = Path('app/src/main/java/com/newoether/agora/tool/GitHubWorkspaceToolProvider.kt')
t = p.read_text(encoding='utf-8')
old = '''        ToolDefinition(function = ToolFunction(name, description, ToolParameters(properties, required)))'''
new = '''        ToolDefinition(function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ))'''
if t.count(old) != 1:
    raise SystemExit(f'ToolFunction helper matches={t.count(old)}')
p.write_text(t.replace(old, new), encoding='utf-8')

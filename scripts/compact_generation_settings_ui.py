from pathlib import Path

p = Path('app/src/main/java/com/newoether/agora/ui/settings/SettingsGenerationPage.kt')
t = p.read_text(encoding='utf-8')

def once(old: str, new: str) -> None:
    global t
    if t.count(old) != 1:
        raise SystemExit(f'expected one match, got {t.count(old)}: {old[:80]!r}')
    t = t.replace(old, new, 1)

once(
'''        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("generation.md") }
    ) {''',
'''        onBack = onBack,
        actions = { if (showDocFab) DocumentationAction("generation.md") }
    ) {'''
)
once(
'''                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 16.dp)''',
'''                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)'''
)
once(
'''                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),''',
'''                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),'''
)
once(
'''
            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
''',
'''
'''
)
# Both generation-slider overloads use the same roomy geometry. Compact all occurrences.
t = t.replace('.padding(horizontal = 16.dp, vertical = 16.dp)', '.padding(horizontal = 16.dp, vertical = 10.dp)')
t = t.replace('Spacer(modifier = Modifier.width(16.dp))', 'Spacer(modifier = Modifier.width(12.dp))')
t = t.replace('modifier = Modifier.fillMaxWidth().padding(top = 8.dp)', 'modifier = Modifier.fillMaxWidth().padding(top = 4.dp)')
p.write_text(t, encoding='utf-8')

p = Path('app/src/main/java/com/newoether/agora/ui/common/ThinkingControlPanel.kt')
t = p.read_text(encoding='utf-8')
t = t.replace('private val BudgetToggleToSliderSpacing = 32.dp', 'private val BudgetToggleToSliderSpacing = 12.dp')
t = t.replace('Spacer(modifier = Modifier.height(32.dp))', 'Spacer(modifier = Modifier.height(12.dp))')
t = t.replace('Spacer(modifier = Modifier.width(16.dp))', 'Spacer(modifier = Modifier.width(12.dp))')
t = t.replace('modifier = Modifier.fillMaxWidth().padding(top = 8.dp)', 'modifier = Modifier.fillMaxWidth().padding(top = 4.dp)')
p.write_text(t, encoding='utf-8')

# Ensure the output policy is actually wired; the previous preparatory script had not run.
p = Path('app/src/main/java/com/newoether/agora/workspace/WorkspaceAgentRunner.kt')
t = p.read_text(encoding='utf-8')
old = '''                        previousResult = result.text
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.SUCCESS,
                            result = result.text,
                            error = null,
                        ), active = null)'''
new = '''                        val visibleResult = WorkspaceOutputPolicy.sanitize(result.text)
                        previousResult = visibleResult
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.SUCCESS,
                            result = visibleResult,
                            error = null,
                        ), active = null)'''
if t.count(old) != 1:
    raise SystemExit(f'workspace output policy match count={t.count(old)}')
p.write_text(t.replace(old, new), encoding='utf-8')

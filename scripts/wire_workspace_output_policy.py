from pathlib import Path
p=Path('app/src/main/java/com/newoether/agora/workspace/WorkspaceAgentRunner.kt')
t=p.read_text(encoding='utf-8')
old='''                        previousResult = result.text
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.SUCCESS,
                            result = result.text,
                            error = null,
                        ), active = null)'''
new='''                        val visibleResult = WorkspaceOutputPolicy.sanitize(result.text)
                        previousResult = visibleResult
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.SUCCESS,
                            result = visibleResult,
                            error = null,
                        ), active = null)'''
if t.count(old)!=1: raise SystemExit(f'matches={t.count(old)}')
p.write_text(t.replace(old,new),encoding='utf-8')

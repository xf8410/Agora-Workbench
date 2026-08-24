from pathlib import Path


def replace(path, old, new, count=1):
    p=Path(path); t=p.read_text(encoding='utf-8')
    if t.count(old)!=count: raise SystemExit(f'{path}: expected {count}, got {t.count(old)}')
    p.write_text(t.replace(old,new),encoding='utf-8')

# Extend execution engine only at its public entry and locked implementation. Loop entry keeps defaults.
p='app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt'
replace(p,
'''        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
    ): Result = automationExecutionGate.withExecution {''',
'''        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
        githubWorkspaceMode: Boolean = false,
        githubAllowedRepositories: Set<String> = emptySet(),
    ): Result = automationExecutionGate.withExecution {''',1)
replace(p,
'''                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                precondition = precondition,
            )''',
'''                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                precondition = precondition,
                githubWorkspaceMode = githubWorkspaceMode,
                githubAllowedRepositories = githubAllowedRepositories,
            )''',1)
# loop call into locked implementation needs defaults explicitly
needle='''            foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            precondition = precondition,
        )'''
t=Path(p).read_text(encoding='utf-8')
pos=t.find(needle)
if pos<0: raise SystemExit('loop locked call not found')
t=t[:pos]+t[pos:].replace(needle,'''            foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            precondition = precondition,
            githubWorkspaceMode = false,
            githubAllowedRepositories = emptySet(),
        )''',1)
Path(p).write_text(t,encoding='utf-8')
replace(p,
'''        foregroundServiceManagedExternally: Boolean,
        precondition: suspend () -> Boolean,
    ): Result {''',
'''        foregroundServiceManagedExternally: Boolean,
        precondition: suspend () -> Boolean,
        githubWorkspaceMode: Boolean,
        githubAllowedRepositories: Set<String>,
    ): Result {''')
replace(p,
'''                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            )''',
'''                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                githubWorkspaceMode = githubWorkspaceMode,
                githubAllowedRepositories = githubAllowedRepositories,
            )''',1)

# Generation context and dispatch guard.
p='app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt'
replace(p,
'''    val automationToolsEnabled: Boolean = false,
    /** Workers use WorkManager''',
'''    val automationToolsEnabled: Boolean = false,
    /** True only for the ordered workspace scheduler, never ordinary Task/Loop automation. */
    val githubWorkspaceMode: Boolean = false,
    /** Exact repositories available to this workspace stage. Empty outside workspace mode. */
    val githubAllowedRepositories: Set<String> = emptySet(),
    /** Workers use WorkManager''')

# GitHub generic provider: enforce workspace repo scope and keep CI off main/master.
p='app/src/main/java/com/newoether/agora/tool/GitHubToolProvider.kt'
replace(p,
'''        fun boolArg(key: String, default: Boolean) = arg(key).toBooleanStrictOrNull() ?: default
        return runCatching {''',
'''        fun boolArg(key: String, default: Boolean) = arg(key).toBooleanStrictOrNull() ?: default
        fun requireWorkspaceRepo(repo: String) {
            if (ctx.githubWorkspaceMode) require(repo in ctx.githubAllowedRepositories) {
                "Repository is outside the active workspace stage"
            }
        }
        return runCatching {
            arg("repo").takeIf { it.isNotBlank() }?.let(::requireWorkspaceRepo)''')
replace(p,
'''                "github_dispatch_workflow" -> {
                    confirmMutation(arg("repo"), "Dispatch ${arg("repo")} workflow ${arg("workflow")} on ${arg("ref", "main")}")''',
'''                "github_dispatch_workflow" -> {
                    if (ctx.githubWorkspaceMode) require(arg("ref", "main") !in setOf("main", "master")) {
                        "Workspace development CI cannot run on main/master"
                    }
                    confirmMutation(arg("repo"), "Dispatch ${arg("repo")} workflow ${arg("workflow")} on ${arg("ref", "main")}")''')

# Workspace provider repository scope.
p='app/src/main/java/com/newoether/agora/tool/GitHubWorkspaceToolProvider.kt'
replace(p,
'''        fun l(key: String) = s(key).toLongOrNull() ?: 0L
        return try {''',
'''        fun l(key: String) = s(key).toLongOrNull() ?: 0L
        if (ctx.githubWorkspaceMode) {
            listOf("repo", "fork_repo", "upstream_repo", "source_repo", "target_repo")
                .map(::s).filter { it.isNotBlank() }.forEach { repo ->
                    require(repo in ctx.githubAllowedRepositories) { "Repository is outside the active workspace stage" }
                }
        }
        return try {''')

# UI: one plan input, ordered run and selected-stage test; no per-stage concurrent execute.
p='app/src/main/java/com/newoether/agora/ui/workspace/GitHubWorkspaceScreen.kt'
t=Path(p).read_text(encoding='utf-8')
t=t.replace('runner.state(state.workspaceId, lane.config.id.name)', 'runner.state(state.workspaceId)')
t=t.replace('runner.state(state.workspaceId, laneKey)', 'runner.state(state.workspaceId)')
t=t.replace('agent.running', 'agent.running')
# Replace run call block.
old='''                                        runner.run(
                                            workspaceId = state.workspaceId,
                                            laneKey = laneKey,
                                            config = lane.config,
                                            request = requests[lane.config.id].orEmpty(),
                                        )'''
new='''                                        runner.runAll(
                                            workspaceId = state.workspaceId,
                                            lanes = state.lanes.map { it.config },
                                            request = requests[lane.config.id].orEmpty(),
                                        )'''
if t.count(old)!=1: raise SystemExit(f'UI run call {t.count(old)}')
t=t.replace(old,new)
t=t.replace('runner.stop(state.workspaceId, laneKey)', 'runner.stop(state.workspaceId)')
# Add test-selected button next to normal run.
anchor='''                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text(" 执行")
                                }
                            }'''
insert='''                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text(" 顺序执行全部")
                                }
                                Button(
                                    onClick = {
                                        runner.testOne(
                                            workspaceId = state.workspaceId,
                                            lanes = state.lanes.map { it.config },
                                            selectedLaneKey = laneKey,
                                            request = requests[lane.config.id].orEmpty(),
                                        )
                                    },
                                    enabled = requests[lane.config.id].orEmpty().isNotBlank(),
                                ) { Text("只测试当前分支") }
                            }'''
if t.count(anchor)!=1: raise SystemExit(f'UI button anchor {t.count(anchor)}')
t=t.replace(anchor,insert)
# State fields changed: use selected stage for result/error/request.
t=t.replace('agent.lastRequest.isNotBlank()', 'agent.request.isNotBlank()')
t=t.replace('agent.lastRequest, style', 'agent.request, style')
t=t.replace('agent.lastResult.isNotBlank()', 'agent.stages[laneKey]?.result.orEmpty().isNotBlank()')
t=t.replace('Text(agent.lastResult, modifier', 'Text(agent.stages[laneKey]?.result.orEmpty(), modifier')
t=t.replace('agent.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }', 'agent.stages[laneKey]?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }')
Path(p).write_text(t,encoding='utf-8')

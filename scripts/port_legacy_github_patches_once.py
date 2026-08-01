from pathlib import Path


def one(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one marker, got {count}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))


provider = "app/src/main/java/com/newoether/agora/tool/GitHubToolProvider.kt"
one(provider,
    "    private val client = GitHubApiClient(context.applicationContext)\n    private val json = Json",
    "    private val client = GitHubApiClient(context.applicationContext)\n"
    "    /** Remote mutations are fail-closed unless the user approves them. */\n"
    "    var confirm: (suspend (summary: String) -> Boolean)? = null\n    private val json = Json")
one(provider,
    '        "github_list_repositories", "github_read_file", "github_create_branch",',
    '        "github_list_repositories", "github_create_repository", "github_read_file", "github_create_branch",')
one(provider,
    '            tool("github_list_repositories", "List up to 100 repositories accessible to the signed-in GitHub account.", emptyMap()),\n            tool("github_read_file",',
    '''            tool("github_list_repositories", "List up to 100 repositories accessible to the signed-in GitHub account.", emptyMap()),
            tool("github_create_repository", "Create a repository after explicit user confirmation.", mapOf(
                "name" to string("Repository name, 1-100 safe characters."),
                "description" to string("Optional description."),
                "private" to ToolProperty("boolean", "Defaults to true."),
                "auto_init" to ToolProperty("boolean", "Initialize main with README; defaults to true."),
            ), listOf("name")),
            tool("github_read_file",''')
one(provider,
    '                "github_list_repositories" -> listRepositories()\n                "github_read_file" ->',
    '''                "github_list_repositories" -> listRepositories()
                "github_create_repository" -> {
                    checkConfirmed("Create GitHub repository ${arg("name")}")
                    createRepository(arg("name"), arg("description"), boolArg("private", true), boolArg("auto_init", true))
                }
                "github_read_file" ->''')
one(provider,
    '                "github_create_branch" -> {\n                    requireWorkbenchBranch(arg("branch"))',
    '                "github_create_branch" -> {\n                    requireWorkbenchBranch(arg("branch"))\n                    checkConfirmed("Create GitHub branch ${arg("repo")}:${arg("branch")}")')
one(provider,
    '                "github_write_file" -> {\n                    requireWorkbenchBranch(arg("branch"))',
    '                "github_write_file" -> {\n                    requireWorkbenchBranch(arg("branch"))\n                    checkConfirmed("Commit ${arg("repo")}:${arg("branch")}/${arg("path")}")')
one(provider,
    '                "github_dispatch_workflow" -> dispatch(arg("repo"), arg("workflow"), arg("ref", "main"))',
    '''                "github_dispatch_workflow" -> {
                    checkConfirmed("Dispatch ${arg("repo")} workflow ${arg("workflow")} on ${arg("ref", "main")}")
                    dispatch(arg("repo"), arg("workflow"), arg("ref", "main"))
                }''')
one(provider,
    "    private suspend fun listRepositories(): String {",
    '''    private suspend fun checkConfirmed(summary: String) {
        if (confirm?.invoke(summary) != true) error("GitHub mutation denied or confirmation unavailable")
    }

    private suspend fun createRepository(name: String, description: String, privateRepo: Boolean, autoInit: Boolean): String {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid repository name" }
        val response = client.request("POST", "/user/repos", buildJsonObject {
            put("name", name)
            if (description.isNotBlank()) put("description", description.take(350))
            put("private", privateRepo)
            put("auto_init", autoInit)
        })
        requireSuccess(response.code, response.body)
        val obj = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", true)
            put("full_name", obj.str("full_name"))
            put("private", obj.bool("private", privateRepo))
            put("default_branch", obj.str("default_branch", "main"))
            put("html_url", obj.str("html_url"))
        }.toString()
    }

    private suspend fun listRepositories(): String {''')

manager = "app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt"
one(manager,
    "    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app)",
    '''    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app).also { provider ->
        provider.confirm = { summary -> onConfirmShellCommand?.invoke("GitHub", summary) ?: false }
    }''')

for path in (
    ".github/workflows/publish-workbench-latest.yml",
    ".github/workflows/port-github-mutations-once.yml",
):
    Path(path).unlink(missing_ok=True)

Path(__file__).unlink()

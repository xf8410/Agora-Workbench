from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if new in s:
        return
    if old not in s:
        raise RuntimeError(f"marker mismatch: {path}: {old[:120]!r}")
    p.write_text(s.replace(old, new, 1))

manifest = "app/src/main/AndroidManifest.xml"
replace(manifest,
'''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />''',
'''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />''')
replace(manifest,
'''        <service
            android:name=".service.AgoraForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />''',
'''        <service
            android:name=".service.AgoraForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />

        <service
            android:name=".uma.UmaWorkbenchService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="User-started localhost game telemetry monitor and overlay" />
        </service>''')

gm = "app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt"
replace(gm,
'''    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app)
    private val shellToolProvider = ShellToolProvider(sandboxFactory).also { stp ->''',
'''    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app)
    private val umaToolProvider = com.newoether.agora.tool.UmaToolProvider()
    private val shellToolProvider = ShellToolProvider(sandboxFactory).also { stp ->''')
replace(gm,
'''        githubToolProvider, shellToolProvider
    )''',
'''        githubToolProvider, umaToolProvider, shellToolProvider
    )''')

settings = "app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt"
replace(settings,
'''        SettingsCategory("github", R.string.settings_github, R.string.settings_github_desc, Icons.Default.Code),
        SettingsCategory("automation", R.string.settings_automation, R.string.settings_automation_desc, Icons.Default.Repeat),''',
'''        SettingsCategory("github", R.string.settings_github, R.string.settings_github_desc, Icons.Default.Code),
        SettingsCategory("uma", R.string.settings_uma, R.string.settings_uma_desc, Icons.Default.Sports),
        SettingsCategory("automation", R.string.settings_automation, R.string.settings_automation_desc, Icons.Default.Repeat),''')
replace(settings,
'''                "github" -> SettingsGitHubPage(onBack = { selectedCategory = null })
                "automation" -> SettingsAutomationPage(viewModel, onBack = { selectedCategory = null })''',
'''                "github" -> SettingsGitHubPage(onBack = { selectedCategory = null })
                "uma" -> SettingsUmaPage(viewModel, onBack = { selectedCategory = null })
                "automation" -> SettingsAutomationPage(viewModel, onBack = { selectedCategory = null })''')

strings = "app/src/main/res/values/strings.xml"
replace(strings,
'''    <string name="settings_github">GitHub Workbench</string>
    <string name="settings_github_desc">Sign in, manage private repositories, Actions, Codespaces, and memory sync</string>
</resources>''',
'''    <string name="settings_github">GitHub Workbench</string>
    <string name="settings_github_desc">Sign in, manage private repositories, Actions, Codespaces, and memory sync</string>
    <string name="settings_uma">赛马娘工作台</string>
    <string name="settings_uma_desc">Agora 内置 SO 端点工具、后台监听和游戏浮窗</string>
</resources>''')

build = "app/build.gradle.kts"
replace(build,
'''        versionCode = 29
        versionName = "1.4.1-workbench"''',
'''        versionCode = 30
        versionName = "1.4.2-workbench"''')

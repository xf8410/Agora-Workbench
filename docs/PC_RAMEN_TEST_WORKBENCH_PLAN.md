# PC 拉面杯测试工作台计划

## 目标

在 Agora Workbench 中增加一个面向上游 PC 源码的测试工作台：

- 运行上游 `xulai1001/umaai-rs`，不混入手机版 `xf8410/ramen-manual`；
- 支持自动完整跑局和后续真实交互式手动游玩；
- 保存可复现的仓库、commit、配置、日志和运行结果；
- 将测试运行关联到聊天，用于判断上游 bug、手机版适配 bug 和暂停恢复 bug；
- 恢复并保留 Agora 的客户端日志、CI 诊断和可追溯 APK 产物能力。

## 固定 PC 测试配置

- Repository: `xulai1001/umaai-rs`
- Ref: `master`（运行开始时必须记录解析后的 commit SHA）
- Scenario: `ramen`
- Trainer: `manual` for interactive mode
- Seed: `20240816`
- Uma: `102601`
- Deck: `302424,302894,303044,302924,303024,303054`

手机版测试必须单独记录：

- Repository: `xf8410/ramen-manual`
- Branch: `workbench/mobile-ramen-apk`
- PR #1 / commit `46ae136d803f1789ffe63a51822c5aeb30988e93`
- Previous CI-passing commit: `2434f6e6e74ce52bdd8d2503ea67a7adbbe41460`

## 阶段

### 0. 基线

- 使用 `workbench/*` 分支；禁止直接修改 `main`。
- 记录当前 main SHA、分支 SHA、Actions run 和构建状态。
- 不把“源码已提交”写成“已验证”。

### 1. 恢复日志和 CI

- CI 构建 PRoot native binaries。
- 单元测试输出实时写入 console log。
- 测试失败时保存 bounded summary 和测试报告 artifact。
- APK 产物包含 version、versionCode、commit、run ID、构建时间和 SHA-256。
- 上传 APK、校验文件和 build manifest。
- 只对精确的 main HEAD 发布 `workbench-latest`。
- 保留失败日志，不通过静默重试掩盖错误。
- 检查并恢复客户端 DebugLog/崩溃诊断入口。

### 2. PC 自动运行基础

- 在 Local Sandbox 固定目录 clone 上游。
- 检查 `rustc`、`cargo` 和 `cargo metadata`。
- 支持 `test_ramen_silent_loop`、`test_ramen_game_full_loop`。
- 保存 stdout、stderr、exit code、timeout、panic、manifest 和最终摘要。
- 自动测试必须明确标记为自动玩家，不能冒充手动游玩。

### 3. PC 测试页面

侧边栏增加独立入口：

- 新建运行；
- 当前运行；
- 自动测试；
- 手动游玩；
- 运行历史；
- 失败详情；
- 发送到聊天分析。

### 4. 交互式手动游玩

- 使用 PTY 启动 `ramen_manual`。
- 支持上下键、回车、Ctrl+C、实时输出、暂停、继续、停止。
- 记录原始终端输出和每次用户选择。
- 不把大量终端内容直接塞入聊天上下文。

### 5. 覆盖与复现

- 多 seed 批量测试发现未知问题。
- 固定 seed 回归已发现问题。
- 强制覆盖回合 2、23、24、47、48、71、72-77。
- 覆盖不吃面、吃面、隐藏风味、训练、休息、出游、比赛、事件和超级拉面。
- 记录 turn/stage/action/pending 状态，并检查状态不变量。

### 6. 聊天修 bug

- 每次运行生成 `runId`。
- 聊天可以读取 manifest、失败摘要、指定回合和有限日志。
- 支持“分析当前失败”“分析这个回合”“比较 PC 与手机版”“生成 bug 报告”。
- 代码修改必须进入 `workbench/*` 分支并经过确认。
- 修复后运行固定回归测试，再决定是否创建 PR。

### 7. 上游协同

Agora 不能替代上游核心能力。后续上游需要提供：

- 固定 RNG 的 headless probe；
- 结构化 JSON 结果；
- 决策日志；
- 完整游戏快照；
- 可恢复 RNG；
- 暂停恢复测试。

## 验收标准

### PC 完整流程

- 能启动；
- 能推进到第 77 回合；
- 事件选择后继续；
- 拉面、隐藏风味、训练选择正常；
- 无 panic、重复事件、卡死；
- 输出 score、PT、RMJ、地区和超级拉面结果。

### Agora CI

- 单元测试失败时保留日志 artifact；
- APK artifact、manifest、SHA-256 一一对应；
- main HEAD 构建身份可验证；
- 失败不会被自动提交污染 main；
- Release 只发布精确验证过的 main HEAD。

## 当前状态

- 工作分支：`workbench/pc-ramen-test-workbench`
- 当前阶段：基线与 CI/日志恢复
- 尚未宣称任何 PC 实机运行或 APK 构建已经通过。

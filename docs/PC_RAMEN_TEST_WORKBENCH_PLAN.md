# PC 拉面杯测试工作台计划（以上游最新版本为基线）

## 0. 上游基线（必须动态记录）

上游仓库：`xulai1001/umaai-rs`
默认分支：`master`
本次抓取 commit：`ead54762fedc25cafdf2759846d4396a6333aa40`

最新上游重点：

- 新增 `.trae/documents/tests_overview.md`，列出 121 个测试；
- 拉面杯相关测试 99 个；
- 配置加载集中化、`UMAI_DATA_DIR`、统一校验；
- 第三年地区 Fixed 策略；
- 回合菜单约束；
- 排名数据和拉面杯参数补全；
- `ramen_manual` 继续使用真实 `RamenGame` + `ManualTrainer`；
- `ramen_manual` 当前使用系统熵源生成 RNG，手动入口不保证复现。

Agora 运行记录必须保存：

```text
upstream_repo
upstream_ref
upstream_commit
upstream_commit_time
upstream_tests_overview_sha
upstream_config_sha
upstream_gamedata_sha
```

不能把 `master` 当作稳定版本。每次同步都必须解析并固定实际 commit。

## 1. 核心原则：快速同步 + 隔离层

上游正在高频更新，Agora 不应复制、修改或长期维护 `umaai-rs` 核心代码。采用以下边界：

```text
Agora Workbench
  ├── UpstreamSync：clone/fetch/固定 commit/生成 manifest
  ├── UpstreamTestRunner：按上游文档执行测试
  ├── RamenRunAdapter：解析有限的测试结果和日志
  ├── RamenDecisionAdapter：只在需要时适配结构化决策
  └── ChatContext：把运行产物关联到 runId

xulai1001/umaai-rs
  ├── crates/umasim/src/game/ramen/     原样使用
  ├── crates/umasim/src/bin/ramen_manual.rs
  ├── .trae/documents/tests_overview.md
  └── 上游 gamedata/config/tests
```

### 不允许的做法

- 不把上游 `game/ramen` 复制进 Agora；
- 不直接修改上游规则代码来适配 Agora；
- 不依赖行号、日志中文文本或内部字段布局作为长期 API；
- 不把一个 seed 的结果当作全部规则正确；
- 不把工作分支中的临时 patch 当作上游同步方案。

## 2. 上游同步机制

### 2.1 版本清单

每次运行前：

1. fetch 上游 `master`；
2. 读取最新 SHA；
3. 生成 `upstream-manifest.json`；
4. 以 commit SHA 建立不可变运行目录；
5. 记录 tests overview、Cargo.lock、配置和关键数据文件 SHA；
6. 运行结束后把 manifest 和结果与聊天 `runId` 关联。

### 2.2 自动检测更新

Agora 提供：

- “检查上游更新”；
- “同步最新上游”；
- “使用当前固定 commit 重跑”；
- “比较两个上游 commit”；
- “更新后运行测试矩阵”。

同步前显示：

```text
旧 commit → 新 commit
改变文件数
是否改动 game/ramen
是否改动 tests
是否改动 gamedata
是否改动配置接口
```

### 2.3 变更风险分类

```text
低风险：docs、tests_overview、changelog
中风险：配置、gamedata、trainer、输出格式
高风险：game/ramen、game/traits、RNG、Cargo.lock、数据加载
```

不同风险自动选择不同验证集，不把所有变化都归类为普通更新。

## 3. 测试策略（以最新上游指南为准）

上游目前已有 121 个测试，其中拉面杯 99 个。Agora 不重新发明测试清单，而是：

### 每次同步的快速门禁

```bash
cargo test --workspace --release
```

并保存：

- 121 个测试的通过/失败/忽略数量；
- 编译和运行时间；
- stdout/stderr；
- 失败测试名称；
- 上游 commit。

### 拉面杯门禁

至少执行并记录上游已有测试：

```bash
cargo test -p umasim test_ramen_silent_loop --release -- --nocapture
cargo test -p umasim test_ramen_game_full_loop --release -- --nocapture
cargo test -p umasim test_three_stage_decision_flow --release -- --nocapture
cargo test -p umasim test_combined_decision_path --release -- --nocapture
cargo test -p umasim test_manual_trainer_full_game --release -- --nocapture
```

实际命令以最新 `Cargo.toml` 和上游文档为准，命令失败时读取测试发现结果，不硬编码旧测试名。

### 手动测试

上游 `ramen_manual` 当前：

- 读取根目录 `game_config.toml`；
- 要求 `scenario=ramen`、`trainer=manual`；
- 使用真实 `RamenGame`；
- 使用 `ManualTrainer` + `inquire`；
- 使用系统熵源随机种子；
- 使用上下键、回车和 Ctrl+C。

因此手动测试必须保存实际配置，但不能宣称可由 seed 复现。需要回归时，优先使用上游已有静默测试或新增上游固定 RNG probe，而不是在 Agora 侧偷偷改规则。

## 4. 固定种子与多种子

- 固定 seed：用于回归、PC/手机版对照和 bug 复现；
- 多种子：用于发现随机路径问题；
- 上游当前手动入口不打印 seed，不能把手动日志称为可复现证据；
- Agora 应识别“非复现手动运行”和“可复现自动运行”两类结果。

如果上游愿意增加 probe，建议在上游提供稳定的：

```text
RamenProbeConfig
RamenDecision
TurnSnapshot
GameSummary
RngSeed
```

但该接口必须由上游拥有，Agora 只适配版本化 JSON，不复制 `RamenGame`。

## 5. 隔离层接口

建议 Agora 只依赖以下外部边界：

```text
UpstreamSource {
  repo, ref, commit, workspace_dir
}

TestRecipe {
  command, workdir, env, timeout, expected_exit_code
}

RunManifest {
  run_id, upstream, recipe, config_hash, started_at, ended_at
}

RunResult {
  status, exit_code, failed_tests, summary, artifacts
}

DecisionSnapshot {
  turn, stage, options, selected_index
}
```

日志适配优先顺序：

1. JSON/机器可读结果；
2. JUnit XML/test result；
3. cargo test 标准输出；
4. 人类日志仅作展示，不作核心判断。

## 6. Agora 功能阶段

### 阶段 A：CI 和日志恢复

- 恢复 PRoot 构建；
- 上传单元测试和 Gradle 失败日志；
- APK manifest + SHA-256；
- 精确 commit/run 溯源；
- 客户端日志与运行产物持久化。

### 阶段 B：上游同步器

- clone/fetch 上游；
- 固定 commit；
- 生成 manifest；
- 比较 commit；
- 检测高风险目录变化；
- 不修改上游工作树。

### 阶段 C：测试运行器

- 自动执行上游 tests overview 对应测试；
- `--release`；
- 保存完整日志和结构化摘要；
- 支持固定 commit 重跑；
- 支持多 seed 任务（仅针对上游提供的 seed/probe 接口）。

### 阶段 D：独立测试页面

侧边栏入口：

```text
PC 拉面杯测试
  ├── 上游版本
  ├── 同步/比较
  ├── 测试矩阵
  ├── 自动运行
  ├── 手动运行
  ├── 历史记录
  └── 发送到聊天
```

### 阶段 E：聊天修 bug

聊天只读取：

- runId；
- upstream manifest；
- 失败测试；
- 指定回合/快照；
- 有界日志；
- commit diff。

修复上游代码时，必须创建上游 `workbench/*` 分支或 PR；Agora 自己只修改隔离适配层。

## 7. 验收标准

### 同步

- 上游更新后不需要手工复制文件；
- 每次运行可精确还原 commit；
- 上游规则改动不会污染 Agora 源码；
- 可比较更新前后的测试结果。

### 测试

- 121 个测试的结果可见；
- 拉面杯 99 个测试可单独查看；
- 测试运行使用 release 模式；
- 手动测试明确标记为不可复现（除非上游提供 seed）；
- 失败日志和 artifacts 可发给聊天分析。

### 客户端

- Agora 本身的 CI、APK 和客户端日志不回退；
- 新功能不阻塞聊天；
- 长测试可后台运行、恢复和取消；
- 不把完整日志一次性塞入上下文。

## 8. 当前工作状态

- Agora 分支：`workbench/pc-ramen-test-workbench`
- Agora 分支基线：`main` SHA `4f307e3a1c1b961a76baa81f2b9f29fc329ab7e5`
- 上游最新抓取：`ead54762fedc25cafdf2759846d4396a6333aa40`
- 本阶段：更新计划，等待按新上游边界实现
- 未宣称上游测试或手机版测试已通过

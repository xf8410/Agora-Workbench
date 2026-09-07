# ADR：拉面杯上游、下游与 Agora 的职责边界

状态：Accepted（工作分支方案基线）

## 决策

采用三层核心架构和一个独立工程工作台：

```text
上游
  uma-autoupdate：更新层
  umaai：通道层
  umasim：核心层

手机运行链路
  游戏 → hlpatch:18765 → 回合状态 JSON
       → uma-juece → ramen-decide.so → 决策 JSON → 浮窗

工程工作台
  Agora Workbench：同步、构建、测试、契约审查、日志归档、聊天分析
```

Agora 不进入游戏实时决策数据通路，也不替代 `uma-juece`。Agora 可以调用 hlpatch 的诊断端点、查看运行样本和分析失败，但不是手机每回合决策所依赖的常驻组件。

## 唯一稳定边界

稳定边界是版本化 JSON 契约，而不是上游 Rust 内部类型：

```text
RamenTurnIn  + schema_version
RamenDecisionOut + schema_version
```

版本采用三轴：

- `schema_version`：契约兼容性；
- `umasim_rev`：决策内核版本；
- `data_version`：gamedata/策略版本。

每个 Agora 测试运行必须保存三轴版本及文件哈希。

## 两手准备

### 手一：上游零改动

- 下游 `ramen-decide` 用 git `rev` 锁定 `umasim`；
- 不 fork、不 vendor 上游规则；
- 上游接触面集中在 `turn_import`、`decide`、`output`；
- 必要补丁只增加 Android feature/platform gate，不修改规则；
- 新上游 commit 先在 CI 试编译和样本回归，通过后再 bump 正式 rev；
- 上游动荡或无关变更可以暂缓同步。

### 手二：上游稳定接口

满足以下条件后切换：

1. 上游公开 turn import；
2. 上游公开 decision out；
3. 契约版本化；
4. `umasim` 最小 feature 集可编译到 `aarch64-linux-android`。

切换后删除本地平台补丁，以官方 API 替换适配实现，并做新旧双输出对拍。

## 上游建议（不作为下游阻塞条件）

- 固化 `uma-autoupdate / umaai / umasim` 三层依赖方向；
- `umasim` 核心零 UI、零网络、零 watcher；
- features：`core`、`cli`、`onnx`、`watcher`；
- 提供 `from_external_state`/turn import；
- 提供 `decide`/DecisionOut；
- 冻结输入输出 schema；
- 补全更新清单中的拉面杯数据；
- 可选将启发式权重数据化。

## 手机下游实施顺序

1. schema v0.1、字段可信度表和 3-5 份真实样本；
2. host `turn_import` 和状态恢复对拍；
3. RandomTrainer 打通决策 JSON，仅作为链路验证，不作为最终策略质量证明；
4. Android `ramen-decide.so`，JNI 仅收发 JSON；
5. gamedata assets fallback + 清单热更；
6. 上游官方 API 落地后迁移。

## 关键验收

### 输入契约

- 字段标注“已验证 / 启发式 / 待确认”；
- 未验证字段不得冒充事实；
- 必须覆盖 RamenSelect、SpecialSelect、Train、RegionSelect、RMJ 和终局状态；
- `pending_ramen`、`pending_special_targets`、`combined_decision` 的阶段语义明确。

### 状态恢复

- 候选动作与预期一致；
- persons、distribution、current_effect、事件计数和 deck_can_split 等派生状态有明确恢复策略；
- 恢复误差必须记录，不能静默填默认值后宣称一致。

### Android

- 无 PC、无 adb、无网络也能使用内置数据出决策；
- 决策后台执行且有超时；
- 失败降级到现有 `TrainingEvaluator`；
- 数据更新校验后原子替换，失败回退 assets；
- `hlpatch` 只输出状态，不注入决策规则。

## Agora 职责

Agora 新页面和工具只负责：

- 检查、比较和固定上游 commit；
- 运行上游 121 测试及拉面杯测试集；
- 试编译新 rev；
- 运行契约样本回归；
- 比较新旧 decision JSON；
- 构建/查看 hlpatch、uma-juece、ramen-decide CI；
- 归档 manifest、日志、APK/.so 和校验值；
- 用 runId 交给聊天分析；
- 在确认后创建工作分支/PR。

Agora 不负责：

- 每回合实时决策；
- 复制 `RamenGame`；
- 自行定义与上游冲突的规则；
- 将文本日志解析当作正式契约；
- 自动把未经回归的新 `master` 投入手机正式链路。

## 上游同步门禁

```text
发现新 master
→ 生成 commit diff 和风险分类
→ workspace release tests
→ 拉面杯测试集
→ host adapter compile
→ Android cross-compile smoke
→ schema compatibility
→ 样本/双输出对拍
→ 人工确认 bump rev
```

高风险路径：

```text
crates/umasim/src/game/ramen/
crates/umasim/src/game/traits.rs
crates/umasim/src/gamedata/
Cargo.lock
```

文档更新可以自动同步元数据，但不能触发正式 rev 自动升级。

## 安全与发布

- 上游 `master` 仅作为候选来源；运行使用不可变 SHA；
- 正式手机内核只使用通过门禁的 rev；
- 所有远程写入、PR 和合并继续要求确认；
- Agora CI 不自动提交失败日志到源码分支；日志作为 artifact 保存；
- 发布物必须携带 commit、run ID、schema、内核和数据版本。

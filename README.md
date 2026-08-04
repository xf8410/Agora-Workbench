# Agora Workbench 已验证补丁与功能说明

> 本节是仓库当前的权威说明，按源码、提交和 GitHub Actions 结果编写。下方原有项目介绍完整保留，用于延续既有说明。未经成功构建验证的修改不会写成“已修复”。

## 当前版本与验证基线

- Android applicationId：`com.newoether.agora.workbench`
- 版本代码：`33`
- 版本名称：`1.4.5-workbench`
- 当前修复分支：`workbench/fix-reply-disappears-on-next-send`
- 已验证的上一阶段会话修复基线：`workbench/root-fix-conversation-loss-v5-phase5-tests-scroll-anchor`
- 上一阶段成功构建：GitHub Actions Run `30874322459`
- “发送下一条消息后上一条模型回复消失”修复提交：`9821834f35e575a68bd4f9a2725f531f1167a426`
- 上述最新修复的构建验证：进行中；在对应 Actions 成功前不标记为已完成修复，也不作为正式发布依据。

## 补丁修复记录

### 2026-08-04：发送下一条消息后上一条模型回复消失——验证中

已确认的代码问题：正常发送链将新用户消息和新模型占位写入 Room，但当时只把新模型占位追加到内存可见列表。新模型占位的 `parentId` 指向未进入内存列表的新用户消息，路径解析会把它识别为缺失父节点的新组件，导致上一条模型回复退出当前可见路径。

本次修改：

- 新增 `ConversationTurnAppend`，把新用户消息和新模型占位作为同一轮原子加入可见快照；
- 保留此前已经完成的模型回复；
- 相同消息 ID 采用替换而非重复追加；
- 新增 `ConversationTurnAppendTest`，验证路径必须保持为：
  `用户1 → 模型1 → 用户2 → 模型2占位`；
- 实际发送链改为同时发布用户消息和模型占位。

状态：源码提交已经生成；CI 尚未完成，因此本项只标记为“验证中”。

### 2026-08-04：历史分页与加载旧消息时保持视口——已通过构建

相关实现：

- 移除历史消息永久最多显示 500 条的窗口方案；
- 使用 `(timestamp, id)` 严格边界的 keyset 分页；
- 初始加载 24 条，每次向前加载 24 条；
- 页面按消息 ID 去重并累计，不用扩大 LIMIT 后整页替换；
- 历史查询继续使用有界字段投影；
- 加载更早消息后保持原可见锚点，避免阅读位置跳动；
- 增加相同时间戳边界、重叠分页、重复 ID 和分页完整性单元测试。

相关提交：`2a4678c5344428844ad2f4df9e5f68b0d1832295`、`39f4d0e37a3971c3d6ffc376e34e1b4b62032642`、`5757585426ead25a92c635465cac928b20480525`、`863ff7e0d0b35ccbcac5097475cd789071429ac0`。

成功构建：Run `30873057309`、Run `30874322459`。

### 2026-08-04：生成中 checkpoint 与终态持久化验证——已通过后续集成构建

相关实现：

- 模型生成期间每 2 秒把当前正文、思考、状态和 segments 写回原 `conversationId`；
-错误状态强制执行 checkpoint；
- 终态消息最多执行 3 次写入；
- 每次终态写入后按 `messageId` 读回；
- 对 `conversationId`、状态、正文、思考和 segments 逐项验证；
- 未通过读回验证的终态写入不会被当成成功。

相关提交：`ef1a8db5ad71c28bfee8a12f36531bb22f81f5b1`。

该实现已包含在后续成功集成构建 Run `30873057309` 与 Run `30874322459` 中。

### 2026-08-04：切换会话时保留一致消息快照——已通过后续集成构建

相关实现：

- 选择另一会话时不在目标查询成功前清空当前正文；
- 每个会话维护最后一致的消息快照；
- 历史加载失败时保留一致快照，不把列表清空；
- 每条历史消息独立映射，单条损坏不会清空整页；
- collector 使用 `conversationId` 身份门，过期结果不能写入当前会话；
- 第一份目标会话一致快照到达后才结束切换状态。

相关提交：`d690c3f20a9d64c6bb5e468d675749f669bda4ce`。

该实现已包含在后续成功集成构建 Run `30873057309` 与 Run `30874322459` 中。

### 2026-08-04：回复下方异常空白与打开历史定位——已通过构建

相关实现：

- 移除生成阶段动态扩大尾部空白的实现；
- 打开已有会话时等待 `LazyColumn` 完成布局后定位到最新回复区域；
- 不再以“最后一条用户消息顶部”作为历史会话打开位置。

相关提交：`c4903e1dce3d4a00c7caff6f94252bab32e52a35`。

成功构建：Run `30867624813`；该实现也包含在后续成功集成构建中。

### 2026-08-03：GitHub 读取、PR、Actions 监控与本地克隆工具——已进入成功构建基线

已纳入源码的 GitHub 工具包括：

- 仓库列表、文件/目录读取；
- 分支列表、提交列表、Git tree、仓库内代码搜索、ref 比较；
- Pull Request 读取及 changed files/checks；
- Actions runs、run jobs/失败步骤元数据、artifact 元数据；
- 创建 `workbench/*` 分支、写入单个 UTF-8 文件、触发 workflow；
- 创建 PR；
- 按精确 40 位 head SHA 校验后合并非 Draft PR；
- 持久化监控 Actions run，并查询或停止本地监控；
- 在 F-Droid Local Sandbox 可用时，浅克隆仓库到固定目录 `/workspace/repos/<owner>/<repo>`。

远程修改受确认门控制；直接写 `main/master` 被拒绝；写文件要求目标为 `workbench/*` 分支；PR 合并校验精确 head SHA。

相关成功构建：Run `30777180549`、Run `30778178167`、Run `30778894085`。后续会话修复成功构建继续包含这些源码。

### 2026-07-31：会话状态串线修复已进入 main 历史

`main` 历史包含：

- `c27425ff73c697d0139926c7b9a15ed44cd57f56`：防止会话状态串线；
- `9b34b0136f1caadab6f4604d9f822c845148c2f6`：合并已验证会话修复；
- `96294ba793a2c5c78f47ec3263bddeeb8ab749fa`：合入 main。

后续工作分支在该历史之上继续加入按会话隔离的生成状态、流式消息镜像和持久化修复。

## 当前源码确认的客户端功能

以下功能均可在当前分支源码中定位；本清单不把设计稿、未合并旧分支或计划写成现有功能。

### 对话与生成

- Room 本地会话和消息数据库；
- 多会话、消息父子链和分支选择；
- 每会话独立生成状态与停止控制；
- 同一会话生成中再次发送时进入该会话的待发送队列；
- 流式回答、思考内容、工具调用 segments、Token 计数和消息状态；
- 重新生成、编辑消息、删除消息及后代级联删除；
- keyset 历史分页和加载旧消息；
- 会话标题生成；
- 每会话草稿及附件草稿保存；
- 对话搜索、关键词搜索和向量检索入口。

### 模型与网络提供商

- OpenAI、OpenRouter、Qwen、DeepSeek、Groq、Anthropic、Gemini、Ollama；
- 自定义 OpenAI 兼容提供商；
- Android 本地模型配置与本地推理入口；
- 模型列表同步、模型别名、生成参数、思考等级与预算；
- HTTP/SOCKS5 代理设置和 bypass 列表。

### 多模态与内容处理

- 图片附件；
- 视频播放和视频切片；
- PDF 页面选择与渲染；
- 文本文件查看；
- 图片/附件转写；
- 图片生成工具；
- Markdown、LaTeX 和 JSON/工具时间线显示。

### 记忆、检索与数据管理

- 保存的记忆文件列表、读取、新建、编辑、重命名和删除；
- Active Memory 替换、追加、前置和精确 patch；
- Embedding 模型配置、消息索引、RAG 检索与缓存管理；
- 原生数据导入导出；
- Claude 与 GPT 聊天导入；
- 自动备份和自动删除周期设置；
- 对话、记忆、提示词、模型与设置的数据控制页面。

### 工具与自动化

- Web 搜索；
- 本地 PRoot Sandbox；
- 本地文件与 Shell 工具；
- SSH 连接及主机密钥指纹固定；
- 5 字段 cron 后台任务；
- 当前会话 Loop，含间隔、最大循环次数、启动和停止；
- WorkManager 和前台服务执行基础设施。

### GitHub 工作台

- GitHub 登录；
- 仓库、目录、文件、分支、提交、Tree、代码搜索与 compare；
- Actions runs、jobs、失败步骤和 artifact 元数据；
- `workbench/*` 分支创建和单文件提交；
- Workflow dispatch；
- PR 创建、读取和精确 SHA 合并；
- Actions run 持久监控；
- 设置页中的 GitHub 代码工作区；
- F-Droid Local Sandbox 中固定路径浅克隆。

### 赛马娘本地工具

当前 `UmaToolProvider` 连接固定地址 `http://127.0.0.1:18765`，源码提供：

- hlpatch 健康、状态和有界训练摘要；
- Agora overlay 最后一致快照和结构变化；
- 当前事件选项与已完成事件观察；
- Hook 诊断和事件奖励目标诊断；
- 拉面杯有界 transition 读取；
- 脱敏协议元数据；
- sniff 启用/停用与缓冲清理；
- 有界、非修改型本地 GET 端点读取；
- 定点 IL2CPP 类名搜索、字段读取、方法读取和方法名搜索。

该工具明确阻止修改型路由、认证原文、私有文件下载、任意 IL2CPP 调用和原始进程内存读取。

## 验证和发布规则

- “源码已提交”不等于“修复已完成”；
- “CI 成功”只证明对应提交通过当前单元测试与 APK 构建，不替代实机交互验收；
- 新补丁必须记录日期、问题、具体修改、提交和 Actions Run；
- 已有修复先查本节和 Git 历史，禁止在未核对旧实现时重复重写；
- 未完成 CI 的修改必须写“验证中”；
- 只有合入 `main` 且 `main` 精确 HEAD 构建成功的 APK 才能作为正式测试发布候选。

---

<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Agora Workbench" width="120" />

  # Agora Workbench

  **面向个人 AI 工作流的 Android 魔改客户端**

  本仓库是 `xf8410/Agora-Workbench` 的独立维护版本，不是应用商店中的官方 Agora 客户端。
</div>

## 项目定位

Agora Workbench 是在 Android 端持续改造的个人 AI 工作台，重点不是保持上游原版说明，而是为实际使用补充更完整的本地工作区、GitHub 协作、长期记忆、工具调用和受控测试能力。

当前主要方向：

- 多模型与自定义 API 提供商接入
- 本地对话、分支、Token 统计和上下文管理
- GitHub 仓库读取、修改、分支、Actions 与发布闭环
- Android 本地 PRoot 工作区及大文件 Artifact 管理
- 长期记忆、对话归档与上下文仓库同步
- 受控的本地工具和项目专用扩展
- 面向本魔改版的独立构建、版本和 Release 流程

## 当前状态

本项目处于持续开发和实机验证阶段，并非稳定发行版。目前正在集中处理：

1. 客户端重启后对话与 Token 计数的可靠持久化
2. 历史对话白屏、正文缺失和恢复路径
3. 回复结束后异常空白字符
4. GitHub AI 工具集补全及 GitHub 优先路由
5. `workbench/*` 到 `main` 的单一集成门
6. APK、main commit、Actions run 与 Release 的一一对应

在这些项目完成验收前，请把构建视为测试版本，并在升级前保留重要数据备份。

## 已加入的 Workbench 能力

- 独立包名：`com.newoether.agora.workbench`
- GitHub 登录和保存凭据的受控使用
- AI 可读取仓库、浏览目录、创建工作分支、修改文件及查看/触发 Actions
- GitHub 远程代码工作区
- Android 持久工作目录 `/workspace`
- PRoot 沙盒和大型输出 Artifact 化
- 长会话有界加载及 OOM 防护
- 对话、记忆、提示词和设置的导入导出/备份基础能力
- 项目专用工具扩展与本地服务接入

> GitHub 工具集仍在补全。当前 main 中存在的能力以实际源码和构建记录为准，不以旧工作分支或历史说明为准。

## 下载测试版

唯一可信下载来源是本仓库自己的 Releases：

**https://github.com/xf8410/Agora-Workbench/releases**

不要从 F-Droid、Google Play、上游仓库或其他 Agora Release 下载来验证本魔改版，它们不是同一个应用。

下载前应核对：

- 仓库必须是 `xf8410/Agora-Workbench`
- 构建来源必须是 `main`
- Release 标注的 commit 应与待测试 main commit 一致
- 优先核对 APK 的 SHA-256
- 安装后的包名应为 `com.newoether.agora.workbench`

目前发布溯源仍在整改。如果 Release 无法明确对应当前 main HEAD，请暂缓下载并等待新的可验证构建。

## 开发与合并规则

- 功能修改先进入 `workbench/*` 分支
- 分支 CI 成功不等于已经可下载测试
- 只有合入 `main` 且当前 main HEAD 的 APK 构建和发布成功，才算可测试版本
- 禁止旧补丁工作流用陈旧整文件覆盖新实现
- 完成的工作分支必须明确合并或说明阻塞原因，不得长期静默遗留
- 后续 APK 名称和发布清单应包含版本、短 commit、Actions run、构建时间与 SHA-256

## 数据与安全

- API Key、GitHub Token、Cookie 和其他认证信息不得写入仓库、对话归档或构建日志
- GitHub 写操作应在 `workbench/*` 分支进行，并保留用户确认边界
- 大型日志和原始数据应保存在受控 Artifact 中，对话只保留有界摘要
- 项目专用扩展必须采用最小权限、白名单和 fail-closed 策略

## 上下文归档分类

长期上下文仓库必须将不同项目分开保存：

- Agora Workbench：`projects/agora-workbench/`
- 赛马娘 SO / hlpatch：`projects/uma-so/`

两者可以存在集成接口，但故障、计划、代码证据和发布状态不得混写在同一个项目文件中。

## 构建

仓库使用 Kotlin、Jetpack Compose、Room、Android NDK 和 GitHub Actions。测试构建以仓库 Actions 中的 `Build Agora Workbench APK` 为准；但只有与当前 main HEAD 对应且成功发布的构建才是推荐测试对象。

## 说明

本 README 已替换原有官方英文宣传、官方商店入口、F-Droid 入口及上游关联项目说明。此后文档只描述本仓库实际维护的 Agora Workbench 魔改版本。
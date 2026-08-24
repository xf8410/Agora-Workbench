# Fork / Upstream 双分支独立工作区设计

## 产品边界

本功能不是普通聊天中的 GitHub 卡片、流程图或 Widget，也不是 Settings 中现有代码浏览器的简单扩展。

它是与普通聊天记录并列的独立开发工作区：

- 入口位于左侧抽屉，作为“工作区”导航项；
- 点击后切换到独立全屏工作区页面；
- 普通聊天的消息列表、输入框、草稿、上下文和工具状态不在该页面显示；
- 工作区有自己的项目配置、任务列表、运行状态和确认流程；
- 截图只表达“工作区可管理多条任务通道”，不照搬其中布局或流程图样式。

## umaai-rs 当前分支映射

| 工作通道 | Fork 基准 | 上游目标 | 用途 |
|---|---|---|---|
| 实验迭代 | `xf8410/umaai-rs:master` | `xulai1001/umaai-rs:ramen_workbench` | 保留矩阵、工作流、工具、报告与完整实验历史 |
| 正式发布 | `xf8410/umaai-rs:upstream` | `xulai1001/umaai-rs:master` | 只承接最终发布差异 |

`upstream` 是 Fork 中的 Git 分支名，虽然与常见 Git remote 名称相同，但在本模型中明确表示“同步上游 master 的发布基线”。

## 两条任务通道

独立工作区至少提供两个可分别执行的任务通道。

### 1. 实验迭代通道

- 工作基线：Fork 的 `master`；
- 功能分支：Fork 的 `workbench/*`；
- PR 目标：上游 `ramen_workbench`；
- 允许保留完整实验提交、Actions 工作流、矩阵、脚本和报告；
- 创建 PR 时必须明确展示源仓库、源分支、目标仓库和目标分支，禁止仅凭同名分支推断目标仓库。

### 2. 正式发布通道

- 工作基线：Fork 的 `upstream`；
- 功能分支：Fork 的 `workbench/*`；
- PR 目标：上游 `master`；
- 从实验线迁入成果时必须使用 squash 语义，形成少量、可审阅的发布提交；
- 禁止把实验线数百个提交通过普通 merge 带入 `upstream`；
- 发布通道默认不携带跑分工作流和临时矩阵，但可保留最终报告；
- 任何向上游 `master` 的 PR 都必须显式二次确认。

两个通道分别维护：

- 当前基准 SHA；
- 当前功能分支；
- 上游目标仓库与目标分支；
- ahead / behind 状态；
- 未提交变更或远程提交状态；
- PR 编号和检查状态；
- 最近一次任务输出；
- 是否要求 squash。

不得用一个全局“当前分支”同时代表两条通道。

## 导航与 UI

### 左侧抽屉

在普通聊天抽屉中增加一级入口“工作区”，位置与“任务”“新聊天”同级，但不混入会话列表。

进入后关闭聊天抽屉，并由顶层导航切换到独立 `WorkspaceScreen`。返回时回到此前的普通聊天，不创建聊天消息，也不改变当前聊天草稿。

### 独立工作区页面

建议结构：

1. 顶栏：工作区标题、返回、刷新、项目选择；
2. 项目摘要：Fork、上游仓库及登录状态；
3. 通道切换：实验迭代 / 正式发布；
4. 当前通道状态：基准、工作分支、目标、ahead/behind、PR/Actions；
5. 任务列表：该通道自己的任务及运行状态；
6. 操作区：同步基准、创建工作分支、检查差异、运行工作流、创建上游 PR；
7. 危险操作：squash 集成、提交上游 PR、合并 PR，均走明确确认门。

不在普通聊天正文中渲染工作区任务图，不复用聊天输入框充当 Git 命令输入框。

## 任务执行隔离

- 两个通道可以分别排队和执行任务；
- 每个任务必须绑定 `workspaceId + laneId + repository + branch + expectedHeadSha`；
- 一个通道的任务不得隐式读取或修改另一通道的当前分支；
- 页面切换、应用退后台或进程恢复后，任务绑定关系必须仍可验证；
- 远程修改前重新读取 ref，并校验预期 SHA；
- 任务输出进入工作区运行记录，不自动写入普通聊天历史；
- 如需 AI 参与，使用工作区专属会话/上下文，不能复用当前普通聊天的 conversationId。

## GitHub 工具能力调整

### 跨 Fork PR

现有 `github_create_pull_request(repo, head, base, ...)` 把 `head` 强制限制为同仓库 `workbench/*`，无法表达跨 Fork PR。

新接口应显式区分：

- `target_repo`：PR 所在的上游仓库；
- `base`：上游目标分支；
- `source_repo`：Fork 仓库；
- `head`：Fork 内 `workbench/*` 分支；
- API payload 中的 head 由已验证的 Fork owner 与分支拼成 `owner:workbench/...`。

安全规则：

- `source_repo` 必须由登录用户拥有，或通过 GitHub fork 元数据确认是 `target_repo` 的 Fork；
- head 的实际 repo、ref 和 SHA 必须在创建前读取并返回；
- base 必须存在于 target repo；
- 确认文案必须完整显示 `source_repo:head → target_repo:base`；
- 不允许模型传入未经验证的任意 `owner:branch` 字符串绕过检查。

### Fork 与同步

需要补充：

- 读取仓库 fork 元数据和 parent/source；
- 创建 Fork（显式确认）；
- 为工作区保存 Fork / upstream 映射；
- 从上游 ref 同步 Fork 基准分支；
- 同步普通实验基准可采用 fast-forward/明确策略；
- 同步发布基准 `upstream` 时目标树应对应上游 `master`；
- 从实验成果进入发布通道必须使用 squash 任务，而不是普通 merge 历史。

## 权限与确认

不取消现有保护：

- 普通写文件仍只允许 `workbench/*`；
- 不直接写 `main/master`；
- 创建 Fork、同步基准、创建跨 Fork PR、合并 PR均需要用户确认；
- 合并继续要求精确 40 位 head SHA；
- 发布通道默认 merge method 为 `squash`；
- 上游 `master` 的操作显示更强警告；
- Token 不进入聊天、日志、工作区记录或 Git 命令参数。

## 验收条件

1. 左侧抽屉可进入独立工作区，返回后普通聊天状态不变；
2. 工作区不是聊天内 Widget，普通聊天记录中不产生工作区系统消息；
3. 两条通道可保存不同基准、功能分支、任务和 PR 状态；
4. 可以从 Fork 的 `workbench/*` 向上游 `ramen_workbench` 创建跨 Fork PR；
5. PR 页面目标仓库必须是上游，而不是 Fork 自己；
6. 发布通道从 Fork `upstream` 面向上游 `master`；
7. 实验成果进入发布通道时能阻止普通 merge，并要求 squash；
8. 两通道并行任务不会串分支；
9. 所有远程修改仍经过确认门和 SHA 校验；
10. 单元测试覆盖跨 Fork head 生成、仓库关系校验、通道隔离和 squash 策略。

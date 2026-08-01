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

本项目处于持续开发和实机验证阶段，并非稳定发行版。目前暂停修复并保留以下待办：

1. 客户端重启后对话与 Token 计数的可靠持久化
2. 历史对话白屏、正文缺失和恢复路径
3. 回复结束后异常空白字符
4. GitHub AI 工具集补全及 GitHub 优先路由
5. `workbench/*` 到 `main` 的单一集成门
6. APK、main commit、Actions run 与 Release 的一一对应

在这些项目完成验收前，请把构建视为测试版本，并在升级前备份重要数据。

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
- 只有合入 `main` 且当前 main HEAD 的 APK构建和发布成功，才算可测试版本
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

本 README 已替换原有官方宣传、官方商店入口、F-Droid 入口及上游关联项目说明。此后文档只描述本仓库实际维护的 Agora Workbench 魔改版本。
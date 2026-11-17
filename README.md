# 角色扮演 App

本地优先的 Android AI 角色扮演应用。角色、会话、消息、记忆都存在本机，API Key 存系统密钥库，没有服务端，也没有账号体系——填一个自己的模型 Key 就能开始。

[下载最新版](https://github.com/han0han0le0ni/AI-dui-hua_APP/releases/latest) · [合并定稿](AI%E8%A7%92%E8%89%B2%E6%89%AE%E6%BC%94APP-%E5%90%88%E5%B9%B6%E5%AE%9A%E7%A8%BFv2.md) · [产品设计方案](AI%E8%A7%92%E8%89%B2%E6%89%AE%E6%BC%94APP-%E4%BA%A7%E5%93%81%E8%AE%BE%E8%AE%A1%E6%96%B9%E6%A1%88.md)

## 功能

**对话**
- 单角色对话与多角色群聊，流式输出，可随时打断
- 消息编辑、重新生成、从这里继续、删除单条
- 线性截断 + 内存撤销栈：截断后可一键还原，不做分支树
- 旁白模式，用第三人称推进场景

**角色**
- 酒馆角色卡导入：JSON / PNG，兼容 V1 / V2 / V3，含 `character_book` 世界书
- 宏替换：`{{char}}`、`{{user}}`、`{{original}}`、`{{date}}`、`{{time}}`、`{{random}}`
- 手写人设、示例对话、语气与禁忌、口癖、开场白
- 世界书条目：关键词触发 + 常驻条目，按优先级分配预算
- 人设库：保存「我扮演谁」，开新线时直接选

**记忆**
- L0–L5 分层：人设、摘要、语义、情节、情绪、关系投影
- 每 8 轮或 10 分钟后台抽取一次，走 ADD / UPDATE / DELETE / NOOP 对账
- FTS5 全文检索（自带 SQLite，中文逐字分词），按 `bm25()` 排序粗筛
- 记忆可在面板里查看、修改、删除；每条都能追到来源消息

**其他**
- 逐次请求的用量与费用统计
- 请求调试页：完整查看本次发送的 system prompt 与消息列表
- 本地备份与恢复
- 深色 / 浅色主题，Material 3

## 下载安装

从 [Releases](https://github.com/han0han0le0ni/AI-dui-hua_APP/releases/latest) 下载 `RoleplayApp-x.y.z-release.apk` 直接安装。

- 最低 Android 8.0（API 26）
- Release 包已签名；首次安装需允许「安装未知来源应用」
- 安装包约 7.3 MB，其中约 5 MB 是四个 ABI 的自带 SQLite 原生库

## 快速开始

1. 打开「设置 → 供应商」，新建一个配置，填入 Base URL、模型名和 API Key（支持 OpenAI 兼容、Anthropic、Gemini）
2. 回「角色」页，选一个内置角色，或点右上角「导入」选一张酒馆卡
3. 进入角色详情，选「我扮演谁」，点「开这条线」
4. 开始对话；长按消息可编辑、重新生成或删除

## 构建

要求 JDK 17+、Android SDK Platform 37（Android 17）。

```bash
cd RoleplayApp
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/
```

签名信息从仓库根目录的 `keystore.properties` 读取；该文件与 `local.properties`、`.jks` 均不进版本库。没有 `keystore.properties` 时 release 变体不签名，仍可正常构建。

更细的环境版本、模拟器启动命令和两个本机构建坑，见 [RoleplayApp/README.md](RoleplayApp/README.md)。

## 目录结构

```
RoleplayApp/
├── app/src/main/java/com/personal/roleplay/
│   ├── data/
│   │   ├── card/          # 酒馆卡解码与落库（PNG 文本块、V1/V2/V3）
│   │   ├── local/         # Room 数据库、FTS5 索引、事务执行器、头像存储
│   │   ├── remote/        # OkHttp + 手写 SSE 解析
│   │   ├── repository/    # 仓库实现
│   │   ├── security/      # API Key 加密存储
│   │   └── seed/          # 内置角色
│   ├── domain/
│   │   ├── chat/          # 生成编排：上下文 → 请求 → 流式落库
│   │   ├── context/       # prompt 组装、预算、宏替换、token 估算
│   │   ├── memory/        # 分层记忆抽取、对账、检索
│   │   ├── provider/      # OpenAI / Anthropic / Gemini 适配
│   │   ├── truncation/    # 截断、级联与撤销
│   │   └── repository/    # 仓库接口
│   ├── ui/                # Compose 页面（角色、聊天、记忆、世界书、设置、调试）
│   └── di/                # 手写依赖容器
└── app/schemas/           # Room 导出的表结构，必须提交
```

## 技术栈

Kotlin + Jetpack Compose（Material 3）+ MVVM；Room 2.8.5 + KSP，SQLite 用 `androidx.sqlite:sqlite-bundled` 自带（系统 SQLite 没有 FTS5）；网络用 OkHttp 5 + 手写 SSE；序列化用 kotlinx.serialization；图片用 Coil。

## 数据与隐私

- 所有对话数据只在本机 Room 数据库里，卸载即丢失，请定期在「设置 → 备份」导出
- API Key 存在 `EncryptedSharedPreferences`（Android Keystore 加密），不写日志、不进备份明文
- 除你自己配置的模型接口外，应用不向任何第三方发送数据

## 已知限制

- 卡片字段只映射常用子集：`alternate_greetings`、`use_regex`、`secondary_keys` 会被忽略
- 暂不支持导出角色卡（导入已完成，导出待做）
- 群聊每轮指定一位发言人，不做 SillyTavern 的自动选人策略
- 记忆抽取目前读库里的原文，开场白中的宏在抽取 prompt 里仍以字面量出现

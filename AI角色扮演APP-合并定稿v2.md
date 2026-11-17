# AI 角色扮演 App · 合并定稿 v2（对齐实际代码）

> **来源**：`AI角色扮演APP-产品设计方案.md`（v1.1，以下简称「方案 A」）× 早期详细实现方案（以下简称「方案 B」）× `RoleplayApp` 当前代码
> **平台** Android ｜ **性质** 个人自用，不商业化 ｜ **状态** 合并定稿，替代方案 A 的 v1.1
> **判断基准**：代码已经跑通并实测过的，优先于纸面设想；没跑通的设想，按成本重排。

---

## 〇、结论先行

1. **骨架用方案 A，实现细节用代码**。方案 A 的三条硬约束（本地、线性、记忆绑定会话）是正确的，不动。
2. **方案 B 只吸收四点**：关系三轴的概念、场景时间的沉浸诉求、世界观作为硬约束、写操作原子性。其余（Retrofit/Hilt、每轮写记忆、关键词好感度、全局时钟、分支时间线）全部否决。
3. **当前代码已超额完成**：方案 A 里标「可选/最后考虑」的群聊已经实现并实测；还多做了「用户人设库」「回复格式」两个方案里没有的东西。
4. **真正的缺口只有三件**：FTS5 在 Android 上不可用、角色卡导入导出没做、抽取与截断缺事务。其余未做项（关系、时间、语音、主动消息）都属于可选，不该挡路。
5. **不再增加新的「真相表」**。关系与时间如果要展示，只能从记忆派生，不能另存一套数值——否则截断、删除、重生成时必然对不上。

---

## 一、定位与四条原则

**一句话定位**：一个完全本地运行、自己带 Key、自己造角色、对话可随时回溯重生成、记忆可溯源且随对话删除的安卓 AI 角色扮演客户端。

| # | 原则 | 含义 | 来源 |
|---|---|---|---|
| 1 | **数据全在本机** | 无账号、无服务端、无云同步。调模型 API 是唯一网络出口，Key 只存本机 | 方案 A |
| 2 | **对话是线性的** | 不建分支树。回溯 = 截断 + 重生成，旧消息直接删除，不保留版本 | 方案 A |
| 3 | **记忆绑定会话** | 记忆由对话抽取并记录来源；消息被删，由它支撑的记忆随之删除；删会话清空全部记忆 | 方案 A |
| 4 | **不给同一件事存第二份真相** | 消息是唯一事实源；记忆是消息的提炼；关系、时间、心情只能是记忆的**投影**，不单独维护一套数值 | 本次合并新增 |

第 4 条是这次合并最重要的裁决。方案 B 的「三体联动」把记忆、关系、时间线当成三个平级存储，任何一边删改都要同步回滚三处——在截断式重生成的模型下，这个同步成本会指数上升，而且必然出现「消息删了、关系没退」的不一致。定稿改成：**记忆是账本，关系与时间是报表**。

---

## 二、两份方案怎么合

### 2.1 保留方案 A 的部分（不动）

- 三条硬约束与线性对话模型
- L0–L5 分层记忆与注入预算（混合策略：固定上限 + 剩余全给最近原文）
- 记忆抽取「带已有记忆对账」的 ADD/UPDATE/DELETE/NOOP 机制
- 截断式重生成 + 内存撤销栈
- 槽位分配（主对话/抽取/摘要/人设校验/语音）
- 本地直连、Key 不落库、备份不含 Key
- 请求调试面板

### 2.2 从方案 B 吸收的部分（改形后吸收）

| 方案 B 的想法 | 吸收方式 | 为什么改 |
|---|---|---|
| 关系三轴（亲密度/信任/尊重） | 不建表、不做数值注入。需要「养成感」时，从 L4 情感记忆生成一个只读的「关系温度」展示 | L4 本来就是角色心情与关系温度；再存一套数值就是第二真相 |
| 时间推进（暂停/快进） | 只保留「场景时间」：会话上一个可空文本（如「第三天·雨夜」），作为一行注入 | 全局时钟要处理暂停、快进、回溯、跨会话一致，成本远超收益 |
| 世界观规则约束 | 用世界书的**常驻条目**承载规则（已有能力），不新做「世界观导入」 | 世界书 `constant=true` 已经把这件事覆盖了 |
| 写操作原子性 | 记忆抽取的多条指令、截断的级联删除，包进 Room 事务 | 现在逐条落库，中途失败会半写；这是真问题，值得补 |

### 2.3 明确否决的部分

| 方案 B 的做法 | 否决理由 |
|---|---|
| Retrofit + Gson + Hilt | 现有 OkHttp + 手写 SSE + 手写 `AppContainer` 已跑通并实测；换框架只增加抽象层和调试难度，零功能收益 |
| 每轮把「玩家说」「角色说」各写一条记忆 | 这就是把消息复制进记忆表：存储翻倍、溯源失效、抽取对账失去意义 |
| 关键词匹配加减好感度 | 把关系质量交给字符串匹配，`谢谢` 加分、`滚` 扣分，既不准又会误伤剧情台词 |
| 全局时钟 + 时间回溯 + branch 字段 | 与硬约束 2 直接冲突；方案 A 已用「内存撤销快照」解决 90% 的回溯需求 |
| 每轮把关系数值塞进主 prompt | 挤占原文预算，且「亲密度 63/100」对模型几乎没有行为指导意义，不如一句「你们刚吵过架，TA 还在赌气」 |
| 分支时间线与版本链 | 已删除，不复活 |

### 2.4 两份方案的逐项对照

| 议题 | 方案 A | 方案 B | 当前代码 | **定稿** |
|---|---|---|---|---|
| 网络层 | OkHttp + 手写 SSE | Retrofit + Gson | OkHttp + 手写 SSE | 保持代码 |
| 依赖注入 | 手动构造或 Koin | Hilt | 手写 `AppContainer` | 保持代码 |
| 导航 | 底部 2 Tab | 底部 3 Tab（聊天/关系/设置） | 3 Tab（角色/群聊/设置） | 以代码为准 |
| 消息结构 | 线性，无分支 | 线性 + `branch` 字段 | 线性，无分支 | 线性 |
| 记忆归属 | 会话级 L1–L4 | 角色级 `memories` 表 | 会话级 L1–L4 | 会话级 |
| 记忆写入 | 8 轮/10 分钟批量对账 | 每轮写两条 | 8 轮/10 分钟批量对账 | 批量对账 |
| 情节检索 | FTS5 粗筛 + 小模型精挑 | 按时间取最近 5 条 | FTS5（失效）→ 内存打分 + 精挑 | 修好 FTS5 |
| 关系系统 | 可选，M5 | 三轴数值 + 标签 | 未实现 | 不建表，L4 承载 |
| 时间系统 | 无 | 暂停/快进/回溯 | 未实现 | 只留「场景时间」文本，后置 |
| 分支 | 明确删除 | `branch` 区分时间线 | 无 | 不复活 |
| 世界书 | 手动条目 | 粘贴文本让 AI 提规则 | 手动条目 + 关键词/常驻 | 手动优先；AI 导入后置 |
| 角色卡导入 | JSON 先做、PNG 后置 | 无 | 未实现 | 补 JSON，PNG 后置 |
| 用量统计 | `usage_record` | 无 | `usage_record` + `request_log` | 保持代码 |
| 验收节奏 | M1–M5 | Phase 0–4 | M1–M4 已完成 | 重排为 M4.5/M5/M6 |

---

## 三、当前代码的真实进度

### 3.1 架构与依赖

严格单向：`ui → domain ← data`，领域层不 import `android.*`。

| 层 | 实际选型 |
|---|---|
| UI | Jetpack Compose + Material3，Navigation Compose 手写路由 |
| 数据 | Room 2.8.5 + KSP，数据库版本 **4**，显式 Migration（禁 destructive） |
| 网络 | OkHttp 5 + 手写 SSE 解析 |
| 序列化 | kotlinx-serialization |
| Key | Android Keystore + EncryptedSharedPreferences（`key_alias` 落库，密文不落库） |
| 图片 | Coil，立绘存私有目录 |
| 后台 | 目前只有 ViewModel 协程；**WorkManager 尚未引入** |

### 3.2 数据模型现状（v4，12 张表）

| 表 | 职责 |
|---|---|
| `characters` | L0 人设、开场白、示例对话、主题色、偏好接入、内置标记 |
| `world_book` / `world_book_entry` | L5：触发词、内容、优先级、常驻、插入顺序 |
| `provider_config` | 协议、baseUrl、modelId、参数 JSON、能力 JSON、槽位、Key 别名 |
| `sessions` | **`participant_ids`（群聊演员表）**、`user_persona_id`、分段摘要、抽取游标、`group_turn_size` |
| `messages` | `seq`、`role`、**`speaker_character_id`**、`edited`、`provider_snapshot`、token |
| `memory` | L1–L4、`session_id` 必填、置信度、置顶、来源类型、状态 |
| `memory_source` | 记忆 ↔ 消息 多对多溯源 |
| `usage_record` | token、估算费用、耗时、成功/失败 |
| `request_log` | 调试面板的完整 prompt、分区块 token、返回原文 |
| `user_persona` | **全局用户人设库**（方案里没有，代码新增） |
| `app_setting` | 键值设置（目前承载回复格式） |

迁移链 `1→2→3→4` 均已在设备上实跑：v2 加群聊演员表与消息发言人，v3 加人设库，v4 加 `group_turn_size` 与设置表。

### 3.3 已完成功能

| 模块 | 状态 | 备注 |
|---|---|---|
| 角色卡、多线会话、开场白、主题色 | 完成 | 一个角色多条线，记忆互不干扰 |
| 群聊 | **完成（超出方案 A 的计划）** | 每轮只让模型扮演一人；说话人标签三分；轮转由消息反推 |
| 世界书 | 完成 | 关键词匹配最近 2 条消息 + 常驻条目；群聊多成员去重 |
| 接入配置 | 完成 | OpenAI 兼容 / Anthropic / Gemini / Ollama；5 槽位；思考强度按协议映射 |
| 上下文组装 | 完成 | L0–L5 分区块 + 预算截断 + 自定义模板占位符 |
| 记忆抽取 | 完成 | 8 轮或 10 分钟触发；最近 20 轮 + 已有记忆对账；失败静默 |
| 记忆溯源与级联 | 完成 | 删消息/截断/删会话三套范围共用一套算法 |
| 截断撤销 | 完成 | 内存快照；摘要分段随断点丢弃 |
| 消息编辑 / 旁白 / 删除 | 完成 | 编辑标 `edited`，相关记忆标 stale |
| 流式输出与节流 | 完成 | 先落空消息拿 seq；400ms 或 120 字符写库；UI 约 20fps |
| 用量统计 | 完成 | 按日/模型/角色/槽位，估算费用 |
| 请求调试 | 完成 | 完整 prompt + 区块 token + 原文 + 耗时 |
| 备份/恢复 | 完成 | zip 含 11 类数据，不含 Key |
| 用户人设库 | **完成（方案外新增）** | 全局复用，会话引用 id + 正文快照兜底 |
| 回复格式 | **完成（方案外新增）** | 猫箱/星号/纯台词/自定义，全局生效 |
| 角色卡导入导出 | **未做** | 方案 A 说「优先做」的一件事，代码里确实没有 |
| 语音、主动消息、关系、人设校验、记忆衰减 | 未做 | 槽位已预留 `voice` / `persona` |

### 3.4 已验证与未验证

已实测：

- debug/release 均构建成功，release 经 R8 后 7.3 MB（含四个 ABI 的自带 SQLite）
- 冷启动、角色列表、群聊端到端（mock 模型）通过
- 三种协议的真实请求体 18 项断言通过（OpenAI 惩罚项、Anthropic 温度强制 1.0、Gemini thinkingConfig）
- v1→v2 迁移设备实跑，22 项回填断言通过
- release 下 Room、kotlinx.serialization、FTS5 降级路径正常

未验证：

- release 包上的完整 HTTP 链路（只差「测试连接」实际点一次）
- 真实服务商（非 mock）的长对话稳定性
- 记忆规模到几千条后的检索耗时

### 3.5 已知缺陷

| # | 缺陷 | 影响 | 状态 |
|---|---|---|---|
| 1 | **Android 自带 SQLite 无 FTS5** | 记忆粗筛退化为内存字面打分，8.3 的两级检索变一级 | **已修**：换 `BundledSQLiteDriver`，设备实测建表与 `bm25()` 检索通过 |
| 2 | 抽取与截断**未包事务** | 中途失败会留下「指令执行一半」的脏状态 | **已修**：`RoomTransactionRunner` 包裹抽取、截断、撤销、删消息、删会话 |
| 3 | 记忆无上限、无衰减 | 长期使用后记忆只增不减 | 未做 |
| 4 | 角色卡无法导入 | 社区现成角色卡用不上，只能手写人设 | 未做 |
| 5 | 无预算上限提醒 | 只有事后用量，烧超了才知道 | 未做 |

---

## 四、逐项裁决

| 议题 | 方案怎么写 | 代码怎么做 | 定稿裁决 | 理由 |
|---|---|---|---|---|
| 用户人设 | 每条会话各存一段文本 | 全局人设库 + 会话引用 + 正文快照 | **采纳代码** | 同一身份跨角色复用；改一次全体生效 |
| 群聊 | 「成本高，最后考虑」 | 已实现并实测 | **采纳代码，升为核心** | 防串味三处机制已经跑通，是差异化能力 |
| 回复格式 | 无 | 全局预设 + 自定义 | **采纳代码** | 零成本，直接决定观感 |
| 记忆检索 | FTS5 + 精挑 | FTS5 失效 → 内存打分 | **修 FTS5，保留两级** | 设计是对的，缺的是 Android 上的执行环境 |
| 关系系统 | 可选模块，单表 + 数值 | 无 | **不建表** | L4 已是关系状态；数值是第二真相 |
| 时间系统 | 无 | 无 | **只做场景时间文本** | 满足氛围，不做计算与回滚 |
| 世界观导入 | 世界书手动条目 | 手动条目 + 常驻 | **保持手动** | 常驻条目已能表达规则；AI 提规则后置 |
| 角色卡导入 | JSON 先做，PNG 后置 | 无 | **补 JSON，PNG M4.5 后** | 方案 A 自己标了「最省时间的接入点」 |
| 消息收藏 | 长按菜单有「收藏」 | 菜单只有复制/重生成/回到这里/编辑/删除 | **删掉这条承诺** | 没有消费场景（无收藏夹页），不做假入口 |
| 30 天淡忘 | 可选项 | 无 | **不做** | 个人自用，误删风险大于清理收益 |
| 主动消息 | 可选，M5 | 无（未引 WorkManager） | **P2，想做再引** | 会产生 token 消耗，且要处理后台限制 |
| 人设校验 | 抽样打分，槽位预留 | 槽位有，流程未接 | **P1 接抽样提示** | 只提示不改写，成本低 |
| 语音 | M5 | 未做 | **P2** | 调优成本最高，先纯文本 |
| 关系值不回滚 | 7.2 写明 | 无关系 | **随关系模块一起取消** | 没有关系模块，就没有回滚问题 |

---

## 五、最终数据模型

### 5.1 保持现状

12 张表不加不改（除修 FTS5 外）。三条归属规则写死：

1. 消息属于会话，`seq` 会话内单调递增，只用于排序和定位截断点。
2. 记忆属于会话，`session_id` 必填；`character_id` 只表示**这条记忆归属哪个说话人**（群聊必需），不是「角色级记忆」。
3. 溯源只走 `memory_source`，不建外键级联——删除必须先算记忆，不能交给数据库。

### 5.2 明确不新增的表

| 不建的表 | 替代 |
|---|---|
| `relationship` | L4 情感记忆 + 只读展示 |
| `timeline_event` | 消息本身 + L1 摘要 |
| `favorite` | 不做收藏 |
| `memory_embedding` / 向量表 | FTS5 + 小模型精挑 |
| `branch` / `version` | 线性对话 + 内存撤销 |

### 5.3 如果将来一定要加

- **场景时间**：`sessions` 加一列 `scene_time TEXT?`（v5 迁移，仅 ADD COLUMN），组装时在 L1 摘要前加一行。不参与任何计算，截断不回滚。
- **关系展示**：不落库。打开角色详情时从 L4 记忆取最新几条，生成一句「TA 现在对你的态度」，或让辅助槽小模型压缩成一句。缓存只放内存。

---

## 六、最终功能范围

### 6.1 已交付（冻结，不再加需求）

角色卡、多线会话、群聊、世界书、四协议接入、槽位、模型参数、上下文分层、记忆抽取与溯源、级联删除、截断撤销、旁白、用量、调试、备份、用户人设库、回复格式。

### 6.2 P0 收尾（做完才算真正稳定）

| 项 | 做法 | 验收 |
|---|---|---|
| 修 FTS5（**已完成**） | 加 `androidx.sqlite:sqlite-bundled`，Room 用 `BundledSQLiteDriver`；保留 FTS5 SQL 与 `bm25()` | 已在模拟器验证：建表、回填、`bm25()` 命中；日志无 `no such module: fts5`；APK 增量约 5 MB（四个 ABI） |
| 抽取/截断加事务（**已完成**） | 统一走 `RoomTransactionRunner`（`useWriterConnection + Transactor`）；事务内 FTS 刷新延后到提交后，避免抢 writer 自锁 | 已在模拟器验证：截断、撤销、删除单条消息均原子生效；不再使用与驱动不兼容的 `withTransaction` 扩展 |
| 角色卡 JSON 导入导出 | 映射 `name / description / personality / scenario / first_mes / mes_example` 到现有字段；重名提示 | 导入一张社区卡后不编辑即可开聊；导出再导入不丢字段 |
| release 补一次真实 HTTP | 在 release 包上点「测试连接」与一轮对话 | 请求成功、流式正常、无 R8 相关反序列化异常 |

### 6.3 P1 记忆质量

| 项 | 做法 |
|---|---|
| 记忆条数与衰减 | 会话内 L3 设上限；低置信 + 长期未召回的先降级后清理；置顶与手动记忆豁免 |
| 人设漂移抽样 | 每 N 轮把最近对话与人设交给 `PERSONA_CHECK` 槽打分；超阈值只在界面提示「TA 有点不像自己了」，不自动改写 |
| 预算提醒 | 用量页加月预算阈值，超阈值首页提示；不拦截请求 |

### 6.4 P2 可选（按兴趣，不排期）

- 场景时间（一行文本）
- 关系温度展示（只读，从 L4 派生）
- 主动消息（WorkManager + 通知，注意 token 消耗）
- 语音 TTS / ASR
- PNG 角色卡导入（内嵌 `chara` base64 + 立绘提取）
- 世界观的 AI 提取（粘贴文本 → 生成常驻/关键词条目草案）

### 6.5 明确不做

账号、登录、云同步、服务端、角色广场、评分、订阅、内容审核、埋点、AB 实验、分支树、版本链、embedding 向量库、关系数值强制回滚、每轮写对话记忆、关键词好感度、全局时钟。

---

## 七、里程碑重排

| 阶段 | 交付 | 验收标准 |
|---|---|---|
| **M4.5 收口** | FTS5 修复、写操作事务、角色卡 JSON 导入导出、release HTTP 补齐 | 6.2 四项全过；真实模型连聊 50 轮不崩、不脏数据 |
| **M5 记忆质量** | 记忆上限与衰减、人设漂移抽样提示、预算提醒 | 聊 200 轮后记忆条数可控；跑偏能被提示；花费可见 |
| **M6 可选体验** | 场景时间、关系温度、主动消息、语音、PNG 卡（按兴趣挑） | 每项独立可关，不影响核心对话 |

顺序理由：FTS5 与事务是「会悄悄变坏」的问题，必须先修；角色卡导入决定「能不能立刻玩起来」，优先级高于任何新玩法；关系/时间/语音都不影响核心闭环，放最后。

---

## 八、最贵的六个坑（更新版）

1. **FTS5 在 Android 上不存在**
   系统 SQLite 只有 FTS3/FTS4。`MemoryFtsIndex` 建表失败后静默退化为内存打分，功能不崩但检索变慢。修法只有一个推荐项：bundled SQLite，保留现有 SQL 与 `bm25()`。不要为了省几 MB 去改 FTS4 再自己实现排序。

2. **群聊的说话人归属**
   别的角色台词必须留在 `messages` 里、按原顺序排、写成第三人称转述「（名字 说：…）」。搬进 system 会失去互动，放 `assistant` 侧会被代写，用 `【名字】：` 放 `user` 侧会被弱模型当成用户刚说的话。这三条都是实测踩过的，改之前先读 `ContextAssembler.toTurn`。

3. **截断后摘要与记忆的残留**
   摘要按 `seq` 分段，截断丢弃断点之后的分段；记忆按溯源级联。两者缺一，角色就会「记得一段不存在的过去」。

4. **流式写库节流**
   每 token 写库必卡。现有做法是 400ms 或 120 字符落一次，收尾写全文。不要改成逐 token。

5. **Anthropic 的思考参数**
   开思考时 `temperature` 必须为 1、不能传 `top_p`、`max_tokens` 必须大于预算。代码已收敛，不要「顺手」把这些参数透传给所有协议。

6. **事务不能用 `withTransaction` 扩展（已处理）**
   换成 `BundledSQLiteDriver` 后，Room 的 `withTransaction` 会因拿不到 `SupportSQLiteOpenHelper` 直接崩。必须走
   `useWriterConnection + Transactor.withTransaction`。另外事务内不能立刻刷 FTS（会抢同一个 writer 自锁），
   由 `RoomTransactionRunner` 把索引刷新延后到提交之后。抽取、截断、撤销、删消息、删会话都已包进事务。

---

## 九、待你拍板的五件事

| # | 决策 | 选项 | 推荐 |
|---|---|---|---|
| 1 | FTS5 怎么修 | bundled SQLite / 改 FTS4 自算排序 | **bundled（已落地）**：现有代码零改动，多约 5 MB |
| 2 | 角色卡导入先做到哪 | 只 JSON / JSON + PNG | **只 JSON**，PNG 放 M4.5 之后 |
| 3 | 关系系统 | 不做 / 只读派生 / 独立数值表 | **只读派生**，从 L4 生成一句展示 |
| 4 | 场景时间 | 不做 / 会话内文本 / 全局时钟 | **会话内文本**，一行注入，不算不回滚 |
| 5 | 主动消息与语音 | 都不做 / 先做主动消息 / 先做语音 | **先都不做**，等 M4.5 与 M5 稳定后按兴趣挑 |

---

## 十、不做清单（防止过度设计）

- 不加第二套「关系/时间/心情」真相表
- 不为了「像产品」而加账号、云同步、广场、评分
- 不引入 Retrofit/Hilt/向量库/SSE 第三方库
- 不为可选模块改核心表结构
- 不在没有真实使用需求时先做 PNG 卡、语音、Live2D
- 不保留任何「旧版本消息」「分支」「回收站」概念

---

## 附录：方案 B 的代码片段怎么处理

方案 B 里的 Kotlin 示例（`MemoryEntity`、`RelationshipEntity`、`TimelineEventEntity`、`ChatRepository`、`PromptBuilder`、`ChatViewModel`）**不直接采用**，原因：

| 方案 B 片段 | 问题 | 对应现有实现 |
|---|---|---|
| `MemoryEntity` 按角色存 | 与「记忆绑定会话」冲突 | `MemoryEntity` + `MemorySourceEntity` |
| `RelationshipEntity` 三轴 | 第二真相，截断无法回滚 | L4 情感记忆 |
| `TimelineEventEntity` + `branch` | 分支模型已否决 | 消息序列 + L1 摘要 |
| `ChatRepository` 每轮写两条记忆 | 消息复制，溯源失效 | `MemoryEngine.extract` 批量对账 |
| `estimateAffinityChange` 关键词匹配 | 不可靠 | 不做 |
| `PromptBuilder` 字符串拼接 | 无预算、无截断、无群聊归属 | `ContextAssembler` + `ContextBudget` |
| 时间控制面板 | 全局时钟成本高 | 场景时间文本（后置） |

结论：方案 B 适合当作「安卓入门实现参考」，不适合当作本项目的架构依据；它的价值在于提醒了几个工程点（事务、错误处理、流式、Key 安全），这些已经或将要并入 P0/P1。

---

## 十一、GitHub 借鉴清单（界面 / 设计 / 处理）

调研时间：2026-11-17。只收录「看得懂、抄得动、与现有约束不冲突」的东西，每条都标了落点。

### 11.1 看了什么

| 仓库 | 形态 | 实际读了 | 结论 |
|---|---|---|---|
| [Starkka15/PocketTavern](https://github.com/Starkka15/PocketTavern)（39★，Kotlin） | 原生 Compose，SillyTavern 兼容，source-available | `PromptBuilder.kt`（约 1370 行）、`ChatBubble.kt`、`ChatScreen.kt`、`ChatViewModel.kt`、`FEATURES.md` | **本次最有价值**：prompt 管线、气泡渲染、流式跟随都有完整实现 |
| [MiaoWuNYA/rikkahub-sillytavern-android](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)（216★，Kotlin） | RikkaHub Plus/Lantern，原生 Compose | `docs/references/chat-generation-pipeline.md`、README 功能章节 | 生成链路分层、缓存省钱、反空回复的工程做法可借 |
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | 原生 Compose | `AssistantImporter.kt`、`ChatPage.kt`、`ChatDrawer.kt` | 角色卡导入与聊天页结构，已有结论 |
| [Mangi-11/Eta](https://github.com/Mangi-11/Eta)、[Zer0Qing/Muse](https://github.com/Zer0Qing/Muse)、[miaoxworld/NativeTavern](https://github.com/miaoxworld/NativeTavern)、[moeru-ai/airi](https://github.com/moeru-ai/airi) | 多端 | `CharacterCardCodec.kt`、`SillyTavernCard.kt`、`png_text_chunks.dart`、`characterCardV3.ts` | 只取 codec 规则：V2/V3 字段、PNG tEXt/zTXt/iTXt、`ccv3` 优先、未知字段保留 |
| [jialmaster/SillyDroid](https://github.com/jialmaster/SillyDroid)（378★） | WebView + 内置 Node 跑 SillyTavern | README、目录结构 | **不借**：本质是运行时打包，没有原生界面/处理可抄 |
| [weiruchenai1/fawntavern](https://github.com/weiruchenai1/fawntavern)（9★） | 原生 Compose | 模块结构（core/data/domain/feature） | 包名 `me.rerere`，RikkaHub 衍生，无新东西 |
| [Sanitised/ST-android](https://github.com/Sanitised/ST-android)、[wancDDY/ST-Ctrl](https://github.com/wancDDY/ST-Ctrl) | WebView 容器 | — | **不借**：与「原生 + 小体积」目标相反 |
| [fatsnk/forksilly.doc](https://github.com/fatsnk/forksilly.doc)（177★） | 安卓原生，op-sqlite + sqlite-vec | 仅功能列表 | 向量记忆路线与「FTS5 + 分层记忆」冲突，**不借** |
| [ovo066/MoRead](https://github.com/ovo066/MoRead)（133★） | 原生阅读器 + AI 伴读 | 仅功能列表 | 排版引擎与角色扮演无关，**不借** |

### 11.2 界面：三处值得改

**① 流式自动跟随改成「上滑即锁定」（P1，约 20 行）**

我们现在的做法在 [ChatScreen.kt](file:///e:/AI对话/RoleplayApp/app/src/main/java/com/personal/roleplay/ui/chat/ChatScreen.kt#L127-L146)：只要手指在滑就跳过滚动。问题是手指一松，下一批 token 到就又把人拽回底部，想往上翻历史基本翻不动。

PocketTavern 的做法（`ChatScreen.kt:159-204`）多了一个**锁存状态**：

- 检测到「向上滑且正在滚动」→ `userScrolledAway = true`；
- 只有当最后一条的底边进入视口（真的回到底部）才清零；
- 新一轮生成开始时无条件清零。

三行状态 + 一个 `snapshotFlow`，不改数据层。这是本次性价比最高的界面改动。

**② 正文加轻量富文本（P1，手写约 60 行，不加依赖）**

[MessageBubble.kt](file:///e:/AI对话/RoleplayApp/app/src/main/java/com/personal/roleplay/ui/chat/MessageBubble.kt#L203-L219) 目前只把 `*动作*` 渲染成斜体。PocketTavern 的 `ChatBubble.kt:632-800` 是手写分段器，没有引第三方 markdown 库，支持：

- `**粗体**` / `***粗斜体***` / `_斜体_`
- `` `行内代码` ``
- 引号包住的对话单独着色（角色扮演文本里台词占比很高，这条收益最大）

建议只抄「粗体 + 行内代码 + 引号着色」，**不抄**它的精灵/表情命令解析和 HTML `<img>` 兼容——那是给卡作者的老格式擦屁股，我们没有历史包袱。
注意保持现有的 `remember(message.content)` 缓存，否则流式时每个 token 都重扫全文。

**③ 输入框斜杠命令（P2，只动 UI 层）**

PocketTavern 支持 `/sys`、`/ooc`、`/persona`、`/addlore`、`/scanlore`。我们已有「旁白模式」按钮，等价于 `/sys`，剩下的里只有两个值得抄：

- `/ooc`：以 `[OOC: ...]` 注入一句系统说明，不进角色台词；
- `/persona 名字`：等价于现在的「我扮演谁」弹窗，做成命令只是少点两下。

`/scanlore`（把最近对话交给模型抽世界书条目）是它比较特别的功能，但我们世界书是手写的、且没有「确认入库」流程，先不做。

**界面明确不抄**：滑动多候选回复（与「线性 + 撤销栈」冲突，且会引入第二套消息真相）、分支/书签、TTS/STT、聊天背景图、角色立绘、图片消息、扩展 header 盒子（插件系统已否决）、多开场白选择器（要改 `characters` 表，违反 6.5）。

### 11.3 设计：前缀缓存与生成链路

**① 前缀缓存要加「冻结锚点」（P1）**

[RikkaHub Plus](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android) 的省钱核心不是「静态块排前面」这一点（我们已经在 [ContextAssembler.kt](file:///e:/AI对话/RoleplayApp/app/src/main/java/com/personal/roleplay/domain/context/ContextAssembler.kt#L51-L59) 做了），而是**冻结锚点**：前缀一旦确定，本轮之内绝不再变。

我们的问题：L1 摘要、L2 语义、L3 情节、L4 情绪四个区块在静态头部里，每次记忆抽取后内容都会变。只要变了，从那一块开始的全部前缀缓存作废——而记忆几乎每轮都在变。

两种改法，选一种即可：

- **保守**：把这四个区块从 system 头部移到末尾（最近对话之前），人设/世界书/约束留在头部；
- **更保守**：每 N 轮（比如 5 轮）才刷新一次记忆区块，中间轮次用快照。

配套两条硬规则：时间类宏（`{{date}}`/`{{time}}`）按 5 分钟粒度取值；随机类宏（`{{random}}`）在**同一轮重试时**要给出相同结果（用 sessionId + seq 做种子），否则「重新生成」每次都换前缀。

**② 生成链路分层（P2，只借思想，不照搬）**

RikkaHub 把发送前处理拆成 `InputMessageTransformer` 管道（时间注入 → prompt 注入 → 占位符 → 文档 → OCR → 模板），把接收后处理拆成 `OutputMessageTransformer`（流式可视化变换 / 落库变换 / 结束后处理分离）。它的价值是「流式展示的内容」和「存进数据库的内容」可以不同。

我们目前是 [ChatOrchestrator.kt](file:///e:/AI对话/RoleplayApp/app/src/main/java/com/personal/roleplay/domain/chat/ChatOrchestrator.kt) 里一条直线，且没有工具、没有附件、没有 OCR。**不建议现在拆成接口 + 10 个类**，只在需要加第三个变换时再抽一个 `List<(String) -> String>` 的有序管道，够用。

会话引用计数、空闲 5 秒清理、后台生成通知、工具审批状态机——这些是「多会话 + 工具系统」的配套，我们不借。

### 11.4 处理：prompt、世界书、防空回

**① 宏替换（P0，必须在角色卡导入之前）**

状态：**已实现**（`domain/context/MacroResolver.kt`，在 `ContextAssembler` 组装时替换，不写库；开场白在聊天页显示时替换）。

原来全项目没有任何 `{{char}}` / `{{user}}` / `{{original}}` 替换，导入酒馆卡会原样吐出占位符。

PocketTavern 的宏表（`PromptBuilder.kt:1246-1361`）可以照抄一个子集：

| 宏 | 值 | 备注 |
|---|---|---|
| `{{char}}` / `{{charname}}` | 角色名 | 必须 |
| `{{user}}` / `{{username}}` | 用户人设名，未设则「用户」 | 必须 |
| `{{original}}` | 空串 | V2 翻译字段用，必须 |
| `{{date}}` / `{{time}}` | 5 分钟粒度 | 与缓存规则配套 |
| `{{random:a,b,c}}` | 确定性随机 | 用 sessionId+seq 做种子 |
| `{{// 注释 }}` | 空串 | 卡作者常用，便宜 |
| `{{charDescription}}` 等字段别名 | 对应字段 | 可选 |

两个实现细节：

1. 替换位置放在 [ContextAssembler.renderSystemPrompt](file:///e:/AI对话/RoleplayApp/app/src/main/java/com/personal/roleplay/domain/context/ContextAssembler.kt#L289-L299) 之后、`AssembledContext` 返回之前，**不要写进数据库**——导入时写死会导致改名后残留旧名字。
2. 替换会改变长度。现在的区块是先 `fitText` 再渲染，替换后 token 估算会偏小。要么改成先替换再 fit，要么替换后再跑一次预算校正（推荐后者，改动小）。

**② 世界书扫描（P1，只取两点）**

现在的 [matchWorldBook](file:///e:/AI对话/RoleplayApp/app/src/main/java/com/personal/roleplay/domain/chat/ChatOrchestrator.kt#L464-L479) 是「最近 2 条 + 小写包含 + 常驻条目」，简单但漏检明显。

PocketTavern 的 `scanWorldInfo`（`PromptBuilder.kt:999-1078`）做了六件事，只建议抄两件：

- **扫描窗口可配**：它支持每条条目自己的 `scanDepth`（默认全局）。我们至少把写死的 `WORLD_MATCH_WINDOW = 2` 提到 4，或做成设置项；
- **递归触发一层**：首次命中的条目正文里若含其他条目的 key，再扫一遍。酒馆卡里「人物→组织→地点」经常是链式的，不做递归会漏掉后半截。

**不抄**：`probability`（设定随机消失比漏检更难查）、`secondary_keys`、`use_regex`、按 depth 注入到对话中间（我们的世界书统一进 system，位置稳定对缓存更友好）。

**③ 反空回复（P1，约 30 行）**

RikkaHub 的做法：正文空但 `reasoning_content` 非空 → 把推理内容提上来；正文以 `response` 之类的引导词开头 → 剥前缀；仍为空 → 扰动参数重试一次。

我们现在完全没有空回处理：流结束就落库，用户看到一条空气泡。在 `ChatOrchestrator` 的落库前加一道：

- 正文 `trim()` 为空 → 重试 1 次（温度 +0.05，附一句极短引导）；
- 还空 → 落一条可编辑的错误提示，不要写空消息进库（否则会污染摘要和记忆抽取）。

不建议把 reasoning 直接当正文用——角色扮演里思考过程经常是「分析用户意图」，直接展示会出戏。只做「重试 + 不落空消息」。

**④ 角色卡 codec（P0 收尾）**

只抄规则，不抄代码：

- PNG 文本块按 `tEXt` → `zTXt` → `iTXt` 都读，`ccv3` 优先于 `chara`；
- base64 解码后必须是严格 UTF-8 JSON，文件上限 16 MiB；
- V1 卡归一化成 V2 结构再映射；
- V3 的未知字段保留（原样存一份 JSON），方便以后补字段不用重新导入；
- 字段映射沿用 3.5 节结论，`alternate_greetings` 只取第一条进 `greeting`。

**⑤ 已经对齐、不用改的**

- Token 预算顺序：PocketTavern 先算「非历史开销」再裁历史，我们 `AssembledContext` 也是这个顺序；
- 相邻同角色合并 + 首条非 system 补 user 占位：`AnthropicAdapter.mergeTurns` 与 `GeminiAdapter.mergeContents` 已实现，且群聊的归属信息写在行内，不会被合并破坏；
- 常驻（constant）条目、按优先级截断：已有。

### 11.5 明确不借（省得以后又被翻出来）

| 东西 | 出处 | 原因 |
|---|---|---|
| 滑动多候选 / 消息版本栈 | PocketTavern | 与「线性 + 内存撤销栈」冲突，等于第二套消息真相 |
| 分支、书签、Fork 对话 | PocketTavern / RikkaHub | 已在 2.3 / 十节否决 |
| 插件系统（JS 扩展、`PT.setMessageHeader`） | RikkaHub Plus / PocketTavern | 权限、安全、体积全不划算 |
| 工具系统 + 审批状态机 + MCP | RikkaHub Plus | 角色扮演不需要，且会破坏缓存 |
| TTS / STT / 图片生成 / 立绘 | PocketTavern / RikkaHub | 体积与「开箱即聊」冲突 |
| QQ/微信 bot、手机控制、Live2D | RikkaHub Plus / Whisper | 与产品定位无关 |
| sqlite-vec 向量记忆 | Forksilly | 已选 FTS5 + 分层记忆 |
| WebView 套 SillyTavern | SillyDroid / ST-Ctrl | 放弃原生就等于放弃这个项目的前提 |
| 每轮总结成长记忆 | PocketTavern 3000 token 长记忆 | 我们 L1 分段摘要已覆盖，再加一套是重复真相 |

### 11.6 落位（不改现有排期，只加这些）

- **P0（角色卡导入之前必须做完）**：宏替换（已完成）；JSON/PNG 导入（已完成）；导出（待做）。
- **P1（下一轮体验优化）**：自动跟随锁存；正文富文本；反空回复；前缀缓存冻结。
- **P2（有空再说）**：`/ooc` `/persona`；世界书窗口可配 + 递归一层；场景备注（Author's Note 的轻量版，一行文本注在最近对话之前）。

如果只做一件：**宏替换**。不做它，所有酒馆卡导入进来都是坏的，其它优化都白搭。

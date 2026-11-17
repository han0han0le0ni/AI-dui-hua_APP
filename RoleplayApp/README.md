# 角色扮演对话 App

本地优先的 AI 角色扮演工具。数据全在本机：角色、会话、消息、记忆存 Room，API Key 存系统密钥库，立绘存私有目录。没有服务端，没有账号。

## 构建

用 Android Studio 打开 `RoleplayApp` 目录，等待 Gradle 同步完成后直接运行。

命令行构建：

```bash
export JAVA_HOME="/path/to/jbr"      # Windows 用 set JAVA_HOME=...
./gradlew :app:assembleDebug         # 产物 app/build/outputs/apk/debug/
./gradlew :app:assembleRelease       # 产物 app/build/outputs/apk/release/
```

要求 JDK 17+ 与 Android SDK Platform 37。首次构建需联网下载依赖（约 400–700MB）。

### 已验证的环境

下列组合已实际构建通过（`assembleDebug` 与 `assembleRelease` 均成功）：

| 项 | 版本 |
|---|---|
| Android Studio | 2026.2.1（AI-262.9437.185.2621） |
| Gradle | 9.6.0 |
| AGP | 9.4.1 |
| Kotlin | 由 AGP 内置提供（AGP 9 起不再应用 `org.jetbrains.kotlin.android`） |
| KSP | 2.3.12 |
| Android SDK Platform | 37（Android 17） |
| Build-Tools | 36.0.0 |
| 运行时 JDK | 17 / 25 均可（本次用 Studio 自带 JBR 25.0.3） |

产物大小：debug 27.4 MB，release 经 R8 后 7.3 MB（其中约 5 MB 是自带 SQLite 的四个 ABI 原生库）。

### 本机环境的两个坑

**1. Gradle 分发地址**：`services.gradle.org` 会 302 到 `downloads.gradle-dn.com`，该域名在国内网络下 DNS 无法解析，表现为 `Connect timed out`。`gradle/wrapper/gradle-wrapper.properties` 里已改用华为云镜像：

```
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-9.6.0-bin.zip
```

**2. 项目路径含中文**：AGP 在 Windows 上默认拒绝非 ASCII 项目路径，已在 `gradle.properties` 中设 `android.overridePathCheck=true` 放行。若后续 aapt2 / R8 出现路径编码问题，把项目移到纯 ASCII 路径（如 `E:\RoleplayApp`）即可彻底规避。

## 运行

### 方式一：Android Studio（最省事）

1. `File → Open` 选 `E:\AI对话\RoleplayApp`，等 Gradle Sync 结束
2. 顶部设备下拉选 `Medium_Phone_API_37.0`
3. 点绿色三角 Run —— 构建、安装、启动一次完成

### 方式二：命令行

```powershell
# 1. 启动模拟器（这个窗口要一直开着）
& 'E:\Android\emulator\emulator.exe' -avd Medium_Phone_API_37.0

# 2. 另开一个 PowerShell，等开机完成（返回 1 就是好了）
& 'E:\Android\platform-tools\adb.exe' wait-for-device
& 'E:\Android\platform-tools\adb.exe' shell getprop sys.boot_completed

# 3. 安装已构建好的 APK
& 'E:\Android\platform-tools\adb.exe' install -r "E:\AI对话\RoleplayApp\app\build\outputs\apk\debug\app-debug.apk"

# 4. 启动（用 am start，别用 monkey）
& 'E:\Android\platform-tools\adb.exe' shell am start -n com.personal.roleplay.debug/com.personal.roleplay.MainActivity
```

> **不要用 `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1` 来启动。**
> monkey 本质是 UI 模糊测试器，会额外注入随机触摸事件。实测它会自己点进设置页、
> 甚至弹出服务商模板面板，让人误以为首启逻辑有问题。`am start` 才是确定性的启动方式。

真机同样走第 3、4 步：手机开 USB 调试、插上、`adb devices` 能看到设备即可。

改了代码要重新打包：

```powershell
$env:JAVA_HOME = 'E:\Program Files\Android\Android Studio\jbr'
cd 'E:\AI对话\RoleplayApp'
.\gradlew :app:assembleDebug
```

### 跑起来之后

首启会自带两个示例角色（林深、苏叶），但**还不能聊天**——得先接一个模型：

**设置 → 接入配置 → 右上角 + → 选模板 → 填 baseUrl / 模型 ID / API Key → 保存 → 测试连接**

连通后回角色列表点进去就能对话。想验证记忆与截断，聊十几轮后看「记忆」面板和「调试」页。

### 包名说明

debug 版带 `.debug` 后缀，包名是 `com.personal.roleplay.debug`，可以和 release 版共存。release 产物是**未签名**的（`app-release-unsigned.apk`），要装机需先配 signingConfig 签名。

## 导出 APK

| 产物 | 大小 | 用途 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 27.4 MB | 自己装、调试。**未混淆，能直接安装** |
| `app/build/outputs/apk/release/app-release.apk` | 7.3 MB | 分发。已签名，经 R8 混淆 |

```powershell
$env:JAVA_HOME = 'E:\Program Files\Android\Android Studio\jbr'
cd 'E:\AI对话\RoleplayApp'

.\gradlew :app:assembleDebug        # debug APK
.\gradlew :app:assembleRelease      # 已签名 release APK
.\gradlew :app:bundleRelease        # 上架 Play 用的 AAB
```

debug 版包名带 `.debug` 后缀（`com.personal.roleplay.debug`），和 release 版可以共存。

### 签名

签名信息放在 `keystore.properties`（已被 `.gitignore` 排除），构建脚本读它，
**密码不会出现在构建脚本里，也就不会进版本库**：

```properties
storeFile=roleplay-release.jks
storePassword=<见该文件>
keyAlias=roleplay
keyPassword=<见该文件>
```

`keystore.properties` 不存在时不会创建签名配置——新克隆的仓库照样能构建，
只是产出未签名 APK，不会因为缺密码直接构建失败。

生成的密钥库当前用简单密码，**只适合本地自用、不上架**。要正式分发请换强密码并单独备份。

> **密钥库丢了就永远无法更新已发布的应用。** 同一个包名的后续版本必须用同一份签名，
> 应用商店也不提供找回。`roleplay-release.jks` 和 `keystore.properties` 请一起备份到安全的地方。

## 真机实测结果

在 `Medium_Phone_API_37.0`（Android 17 / API 37）模拟器上实测通过：

- 冷启动 2.0s，`am start` 后正常显示「我的角色」
- 两个内置示例角色（林深、苏叶）正常渲染，主题色、相对时间、底栏导航、FAB 均正常
- 全程无 FATAL、无 ANR

### 群聊端到端验证

用本地 mock 模型服务（回显自己被要求扮演谁）跑通了完整链路：

| 检查项 | 结果 |
|---|---|
| 提示词扮演位 | 第 1 轮「本轮你只演「林深」一个人」，第 2 轮变成苏叶 —— 轮转正确 |
| 同场角色区块 | 苏叶只出现一句简介，**完整人设从未进入扮演位** |
| 历史记录分流 | 用户发言落到 `user` 角色并标「我：」；其他角色同样落 `user` 角色 |
| 消息发言人落库 | `seq=2 → builtin-lin-shen`，`seq=4 → builtin-su-ye` |
| 顶栏预告 | 苏叶说完后显示「下一句：林深」，轮转回绕正确 |
| 世界书聚合 | 两个角色共用一本书时，关键词条目与常驻条目**各注入一次**（已去重） |
| 世界书命中窗口 | 触发词滚出「最近 2 条消息」后，关键词条目自动不再注入 |

### 模型参数验证

对三种协议各发一次真实请求，抓取应用实际发出的请求体，18 项断言全部通过：

| 协议 | 断言 |
|---|---|
| OpenAI 兼容 | `temperature` `top_p` `max_tokens` `presence_penalty` `frequency_penalty` 全部按设置透传；`reasoning_effort: "medium"`；不出现 `thinking` |
| Anthropic | `thinking.type=enabled`、`budget_tokens=12000`；**温度被强制为 1.0**；**开启思考时省略 `top_p`**；`max_tokens` 自动抬到预算之上 |
| Gemini | `generationConfig` 内透传温度/上限；`thinkingConfig.thinkingBudget=1024`、`includeThoughts=false` |

参数面板的界面也逐项确认：协议感知的惩罚项说明、「思考强度」按协议给出对应提示、档位回显正确。

### release 包（R8 混淆后）验证

debug 能跑不代表 release 能跑，混淆会裁掉/重命名代码，所以单独实测过：

- `apksigner verify` 通过：v2 签名有效，证书 SHA-256 与密钥库一致
- 装到模拟器、冷启动正常，`mCurrentFocus` 指向 MainActivity，无 FATAL
- **Room 正常**（角色列表渲染）
- **kotlinx.serialization 正常**：手输的 `http://127.0.0.1:8765` 保存后重新打开配置页
  仍是这个值，说明「保存编码 → 读取解码」往返在 R8 下完好
- 换成 bundled SQLite 后，release 启动、Room 打开、原生库加载均正常（无 FATAL）

未在 release 上跑到的：HTTP 请求链路（OkHttp + 适配器响应 DTO 反序列化）。
这条路径在 debug 上按三种协议 18 项断言验证过；release 侧只差一次
「设置 → 接入配置 → 编辑 → 测试连接」的实际点击。

### 数据库迁移验证

v1 → v2 迁移在设备上实跑（覆盖安装到已存在 v1 库的应用）：

- `PRAGMA user_version` = 2，`room_master_table` 身份哈希正常 —— Room 接受了新 schema
- `sessions.participant_ids` 为 `TEXT NOT NULL` 且**无默认值**，与实体声明完全一致
  （若当初用 `ALTER TABLE ... DEFAULT '[]'`，这里会因 default 不匹配直接抛异常）
- 索引全部保留，角色数据无损

迁移的回填 SQL 另用 Python 造了一份带 2 个会话 / 6 条消息的 v1 库离线执行，
22 项断言全部通过：`participant_ids` 回填为 `["角色id"]` 且可被 JSON 解析、
`assistant` 消息回填发言人、用户与旁白保持 NULL、索引重建、行数不丢、
无对应会话的孤儿消息不报错。

### FTS5 由自带 SQLite 提供

Android 系统自带的 SQLite 只编译了 FTS3/FTS4，没有 FTS5。早先版本在系统库上建 `memory_fts`
会报 `no such module: fts5`，记忆粗筛只能退化为内存打分。

现在改为使用 `androidx.sqlite:sqlite-bundled` 自带的 SQLite，并通过
`BundledSQLiteDriver` 构建 Room：

- FTS5 与 `bm25()` 都可用，8.3 的「FTS5 粗筛 + LLM 精挑」恢复成完整两级
- 四个 ABI 的原生库各约 1.2 MB，release APK 因此从 2.2 MB 增到 7.3 MB
- 换驱动后 `database.openHelper` 不再可用，`MemoryFtsIndex` 与迁移都改走
  Room 的 `useReaderConnection` / `useWriterConnection`；迁移同时实现了
  `migrate(SQLiteConnection)` 与旧的 `migrate(SupportSQLiteDatabase)` 两个签名

**老库不需要清空**：FTS 表改为第一次用到时懒建；如果索引为空而 `memory` 表有数据，
会按现有记忆自动回填一次。

已在模拟器上实测：输入一条记忆后 `memory_fts` 建表成功、回填 1 条，
`bm25()` 检索能命中；debug 与 release（R8 混淆后）均启动正常、无 FATAL。

### 事务必须走驱动 API

换驱动后 **不能用 Room 的 `withTransaction` 扩展**：它内部调用 `beginTransaction()`，
依赖 `SupportSQLiteOpenHelper`，在驱动模式下会抛
`Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory was configured`。

统一走 `RoomTransactionRunner`：

- 事务边界用 `useWriterConnection { transactor -> transactor.withTransaction(...) }`
- 事务内的 FTS 刷新不能立刻执行（会抢同一个 writer 自锁），先排进
  `DeferredFtsWrites` 队列，提交成功后再刷新；回滚则队列直接丢弃
- 已包进事务：记忆抽取的多条指令与抽取游标、重新整理、截断、撤销、删除消息、删除会话

模拟器实测：截断 1 条消息、撤销还原、删除单条消息均正常，无 FATAL、无卡死。

## 技术选型

| 层     | 选型                                          |
|--------|-----------------------------------------------|
| UI     | Jetpack Compose + Material3（BOM 2026.09.00） |
| 导航   | Navigation Compose，手写路由常量              |
| 数据   | Room 2.8.5 + KSP；AndroidX bundled SQLite（含 FTS5） |
| 网络   | OkHttp 5 + 手写 SSE 解析（不引额外 SSE 库）   |
| 序列化 | kotlinx-serialization 1.11.0                  |
| Key    | Android Keystore + EncryptedSharedPreferences |
| 图片   | Coil 2.7.0                                    |
| DI     | 手写 AppContainer + ViewModelProvider.Factory |

最低支持 Android 8.0（API 26），编译与目标均为 API 37。

> 图标库用的是 `material-icons-extended`，该工件已被 androidx 冻结在 1.7.8、不再随 Compose 升级（BOM 里仍会管理它）。长期看应该把用到的图标改成矢量资源内置进项目。

## 目录结构

```
app/src/main/java/com/personal/roleplay/
├── data/          数据层：Room 实体/DAO、仓储实现、网络、备份、Key 存储
│   ├── local/     数据库、FTS5 索引、中文分词器、立绘存储
│   ├── remote/    OkHttp 基础设施、SSE 逐行读取
│   ├── repository/ 仓储实现（实现 domain/repository 的接口）
│   └── backup/    zip 导出/导入
├── domain/        领域层：纯 Kotlin，不依赖 Android 框架
│   ├── model/     角色、会话、消息、记忆、prompt 区块
│   ├── provider/  模型接入适配层（OpenAI 兼容 / Anthropic / Gemini）
│   ├── context/   token 估算、预算、L0–L5 分层组装
│   ├── memory/    抽取对账、FTS5 粗筛精挑、摘要压缩
│   ├── truncation/ 截断引擎与撤销栈
│   ├── chat/      一次对话的编排
│   └── usage/     用量计量
├── ui/            界面：屏幕、ViewModel、导航、主题
└── di/            依赖装配
```

依赖方向严格单向：`ui → domain ← data`。领域层不 import 任何 `android.*`。

## 群聊

角色列表右上角的「开个群聊」勾选两个以上角色即可。勾选顺序就是发言轮转顺序。

**每次只让模型扮演一个人。** 这是「角色不串味」的全部要害，三处一起保证：

1. **扮演位唯一**：只有本轮发言者的完整人设进入「你的身份与性格」区块，头部直接写死
   「本轮你只演「林深」一个人」。其余角色只进「同场角色」区块，**只给姓名 + 一句简介 +
   与对方的关系，不给完整人设**——给多了模型就会顺手替他们把台词也写了。
2. **每句话都带说话人标记，全留在 messages 里**：

   | 谁说的 | role | 标记 |
   |---|---|---|
   | 当前扮演者自己 | `assistant` | `【我】：` |
   | 那个真人 | `user` | `【用户】：` |
   | 场景描写 | `user` | `【旁白】：` |
   | 别的角色 | `user` | `（名字 说：…）` |

   三个坑都实测踩过，改动这里前先读完：

   - 别的角色台词放 `assistant` 侧（哪怕带名字前缀）：模型会照着 assistant 历史里的行往下写，
     当前角色顺手把别人的台词也写了（「A 角色说 B 角色的话」）。
   - 别的角色台词用 `【名字】：` 直接引语放 `user` 侧：`user` 就同时代表真人和别的角色，
     弱模型只会盯着最后一条 user 消息作答，上一位角色的台词恰好是最后一条，
     于是被当成用户刚说的话。
   - 把别的角色台词整个搬进 system 的「前情」区块：归属确实不会错了，但模型把它当背景资料，
     **后开口的角色接不上前面角色的话**，群里就没有互动了。

   所以最终形态是：台词全部留在 messages 里、按原顺序排着（只有留在对话流里，模型才会
   把它当成正在发生的事来回应），别人那行改写成立**第三人称转述**——归属信息落在句子里，
   弱模型可以忽略一个标记符号，但没法忽略一句自然语言。自己那行也带 `【我】：`，
   模型不必再靠「这一行没有标记」反推「所以是我说的」。

   标记词表统一放在 `domain/model/Prompt.kt` 的 `SpeakerLabels`，拼历史、剥标记、记忆抽取
   三处共用，避免字面量各自漂移。
3. **输出不需要标记**：每轮只有一个人开口，模型直接写这个人的话即可。万一模型仍带上
   `【我】：` 或 `（名字 说：…）`，`ChatOrchestrator.stripOwnSpeakerLabel` 会在落库前剥掉。

群聊里还额外注入一段多人场景约束（锁定扮演对象、禁止代写、声明「以别的名字开头的行
不是你写的」）。记忆抽取与摘要压缩同样会把发言人名字喂给模型，抽取出的记忆按
「最早那条来源的说话人」归属，三个人的记忆不会混在一起。

发言轮转位置**不落库**，而是从「最近一条角色台词的发言人」反推。截断、重生成、改消息
之后轮转会自然跟着回退，不会出现「消息删了但轮转指针还停在后面」的错位。

世界书按**全部成员**聚合，同一本书被多个成员持有时去重，只注入一次。

## 模型参数

接入配置页里的「模型参数」点开是一个参数面板，可调：

| 参数 | 说明 |
|---|---|
| 温度 | 0～2 |
| Top P | 0～1 |
| 单次最大输出 token | 只限制这一次回复的长度，不是上下文窗口 |
| 存在惩罚 / 频率惩罚 | **仅 OpenAI 兼容协议显示**，Anthropic 与 Gemini 接口里没这两个字段 |
| 思考强度 | 关闭 / 低 / 中 / 高 |

**加参数不需要数据库迁移。** `ProviderParams` 整体以 JSON 存在 `provider_config.params_json`，
读取时字段缺失一律走默认值，所以老配置会自动补上默认值、新字段也不影响老库。

### 思考强度为什么是一个档位而不是三个旋钮

同一个「思考」在三家协议里形状完全不同：

| 协议 | 实际发送 |
|---|---|
| OpenAI 兼容 | `reasoning_effort: "low" \| "medium" \| "high"` |
| Anthropic | `thinking: {type:"enabled", budget_tokens: N}` |
| Gemini | `generationConfig.thinkingConfig: {thinkingBudget: N, includeThoughts: false}` |

界面上只给一个档位，由适配器各自映射。选「关闭」时**不发送任何推理字段**——这跟发 `low`
是两回事，非推理模型收到这些字段可能直接报错。

Anthropic 有两条硬性约束，踩中任意一条都是 400，代码里直接收敛而不是丢给用户记：

1. 开启思考时 `temperature` 必须是 1，且不能再传 `top_p`
2. `max_tokens` 必须大于思考预算 —— 会自动抬到「预算 + 1024」之上（`max_tokens` 是上限
   而非预留，抬高不会多花钱）

## 几个关键设计

**上下文分层（L0–L5）**：人设常驻，情景摘要、语义记忆、情节记忆按预算注入，世界书按触发词命中后按优先级注入，最后才是原文对话轮次。超预算时从低优先级区块开始截断。

**记忆溯源级联**：每条记忆记着自己是「由哪几条消息」抽出来的。删消息时反查溯源表——只剩这一个来源的记忆跟着删，还有其他来源的只减来源。所有破坏性操作都会先弹窗列出将要失去的记忆。

**撤销只在内存**：截断快照不落库。离开对话页即视为确认删除，此时数据才真正清理。

**情景摘要分段存**：摘要按消息 seq 分成若干段。截断时直接丢弃断点之后的分段，不需要重新调模型，也绝不会残留已删除对话的内容。

**流式写库节流**：流式输出时先把空消息落库拿到 seq，之后每 400ms 或每 120 字符才写一次库，避免几百轮对话时每个 token 都写一次。

**中文检索**：FTS5 默认分词器对中文无效，这里用自定义分词器把中文按字/词切开再建索引。

## 当前状态

已完成 M1 范围：角色卡与多线会话、**多角色群聊**、世界书、**模型参数调节**、分层上下文组装、记忆抽取与溯源、截断撤销、多服务商接入、用量统计、请求调试面板、本地备份。

代码已通过真实编译验证：`:app:kspDebugKotlin`（Room 全部 DAO 查询、实体、FTS5 建表在编译期校验通过）、`:app:assembleDebug`、`:app:assembleRelease`（含 R8 混淆与资源压缩）全部成功，零编译错误。

群聊与数据库迁移已在模拟器上端到端实测通过，细节见上文「真机实测结果」。

尚未实现（方案里的后续里程碑）：

尚未实现（方案里的后续里程碑）：

- 语音：ASR 输入与 TTS 输出（Voice 槽位已在枚举里预留）
- 情感模型与语气微调
- 人设漂移自检的自动运行（PersonaCheck 槽位已预留，但未接入流程）
- 记忆的定时衰减与自动降级

## 注意

- 备份 zip **不含 API Key**，导入后需要在「设置 → 接入配置」里重新粘贴。
- 用量页的费用是按内置价目表算的估算值，只看量级，不能当对账凭据。价目表在 `domain/model/Usage.kt`，需要改价格直接改那里。
- 卸载 App 会清除全部数据，记得定期导出备份。
- 测试用的模拟器上留了一条名为 **「本地 Mock」** 的接入配置（指向 `http://127.0.0.1:8765`）
  与一个演示群聊，都是验证时的脚手架。接真实模型前请先删掉这条配置。
  要复现验证，用 `py D:\Temp\User\mock_llm.py` 起 mock，并 `adb reverse tcp:8765 tcp:8765`。

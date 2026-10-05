# SmartSkip

无 Root 的 Android 开屏广告智能跳过工具。

不 Root、不用 Magisk / LSPosed / Xposed、不修改系统分区、不重新打包目标 App。全部能力建立在
Android 官方允许的 `AccessibilityService` 之上。

> **设计哲学：宁可漏掉一个广告，也不要替你误点一次危险按钮。**

这个项目最重要的指标不是"跳掉了多少广告"，而是"错误点击了多少次正常按钮"。因此系统默认
**不确定就不点**，而不是"可能是广告就点"。

---

## 目录

- [现在能做什么](#现在能做什么)
- [当前状态](#当前状态)
- [核心机制](#核心机制)
- [安全设计](#安全设计)
- [隐私设计](#隐私设计)
- [构建与安装](#构建与安装)
- [使用流程](#使用流程)
- [调试](#调试)
- [项目结构](#项目结构)
- [测试](#测试)
- [已知限制](#已知限制)
- [与计划书的差异](#与计划书的差异)
- [后续方向](#后续方向)
- [许可](#许可)

---

## 现在能做什么

- 监听前台应用窗口变化，抓取无障碍控件树
- 用**多因素评分**识别开屏广告的跳过控件：文本、位置、时间、UI 结构、Activity、
  历史规则、风险特征
- 对每个控件给出 0-100 的置信度评分，并**逐条列出**每个加减分项
- 硬性否决：输入框、支付/凭据页面、含危险语义的文本、"伪跳过"诱导按钮
- 两阶段确认：点击前重新读取界面树并重新定位控件
- 点击后行为验证：判断是"真的跳过了"还是"点错了"
- 自动学习规则：按「包名 + Activity + 控件结构指纹」索引
- 三种工作模式（安全 / 标准 / 激进）
- 限流与断路器：最小点击间隔、每分钟上限、危险误点后自动暂停该应用
- UI Inspector：查看当前界面控件树以及每个候选的完整评分
- 评估记录：每一次判断的完整依据都可在界面上查看

## 当前状态

已完成计划书的 **Phase 0 - Phase 3**，并部分包含 Phase 4：

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 技术验证 / UI Inspector | ✅ |
| Phase 1 | 最小可用版本（发现跳过 → 判断位置 → 自动点击） | ✅ |
| Phase 2 | 多特征识别 | ✅ |
| Phase 3 | 安全评分系统 | ✅ |
| Phase 4 | 规则学习 | ✅ 已实现 |
| Phase 5 | 异常处理（Canvas / WebView / 自定义 View 等） | ⚠️ 部分 |

**代码是可编译、可安装、经过单元测试的**，但**没有在真实开屏广告上做过大规模实测**。
评分权重是基于推理设定的初值，需要真机数据来校准。这一点很重要：请把首次使用当作
"观察阶段"，先关闭总开关看它怎么判断，再决定是否开启自动跳过。

## 核心机制

### 决策管线

```
无障碍事件
   ↓  合并突发帧（同一窗口只评估最新一帧）
抓取 UI 树  →  不可变快照（不持有 AccessibilityNodeInfo 引用）
   ↓
候选筛选（只评估带"跳过"特征的控件）
   ↓
特征提取
   ├── 文本：跳过词 / contentDescription / resource-id / 广告标识 / 危险语义 / 倒计时
   ├── 位置：右上角 / 左上角 / 右下角 / 正中 / 面积占比
   ├── 时间：启动后多久出现 / 是否有倒计时 / 倒计时是否在递减
   ├── 结构：是否可点击 / 叶节点 / 尺寸稳定 / 该签名是否曾可点击
   ├── Activity：是否启动页 / 是否含广告语义名 / 是否 WebView
   ├── 历史：是否命中已学习规则 / 成功失败次数 / 用户是否确认
   └── 风险：是否在素材内 / 是否有下载支付内容 / 是否多个候选 / 是否居中过大
   ↓
评分（0-100，逐项可解释）
   ↓
① 硬性否决检查（不可被高分推翻）
   ↓
② 阈值检查（按模式 + 信任度调整）
   ↓
③ 限流 + 断路器
   ↓
④ 两阶段确认（重新读取树、重新定位控件）
   ↓
⑤ 执行点击
   ↓
⑥ 点击后验证（等待 600ms 后重新采样）
   ↓
⑦ 结果反馈：提高 / 降低规则置信度；危险误点则暂停该应用
```

### 评分模型

计划书第六节的加权公式被实现为一张**可逐条审计的积分表**，而不是一组浮点权重。
这样做是为了让每个决策都能被复核，也为了从结构上保证一件事：

**没有任何单一特征能够独自跨过自动点击阈值。** 最强的单项正向特征是"命中已学习规则"
（+30），而最低的自动点击阈值是 78（激进模式）。一个只有关键词的控件得分远低于阈值。

评分上限为 100，落在计划书定义的区间里：

| 分数 | 区间 | 行为 |
|---|---|---|
| 0-30 | 不处理 | 完全不认为是广告控件 |
| 30-70 | 观察 | 可能是，但不点 |
| 70-90 | 候选 | 达到阈值则点 |
| 90-100 | 高置信 | 达到阈值则点 |

### 自动点击阈值

| 模式 | 阈值 | 说明 |
|---|---|---|
| 安全 | 92 | 只自动执行已确认的规则 |
| 标准 | 86 | 默认 |
| 激进 | 78 | 可能误点，有明确警告 |

信任度会调整阈值：

- 用户确认过的规则：−8
- 命中已学习规则：−6
- **都没有（首次遇到）**：阈值提升到 **101**，即超过评分上限

最后一条是本项目与"分数够了就点"的关键区别：**一个从未处理过的页面永远不会被自动点击**，
无论它的评分多高。这实现了计划书第九节的"未知 App → 谨慎模式 → 用户确认 → 建立规则 →
下次自动执行"。用有限的惩罚值表达不了这个承诺——一个看起来很像真跳过按钮的伪造按钮，
分数足以越过任何有限惩罚。做成硬门槛才能让这个承诺成立。

## 安全设计

### 硬性否决（高分无法推翻）

以下情况无论评分多高都不点击：

| 否决原因 | 触发条件 |
|---|---|
| 输入框 / 勾选项 | `isEditable` 或 `isCheckable` |
| 支付或凭据页面 | 同屏存在支付/订单/密码类 resource-id，或 Activity 名含支付语义 |
| 危险文本 | 控件自身文本是"立即下载/立即支付/授权/开通"等 |
| 伪跳过诱导 | 同一控件同时有"跳过"文案和"领取/免费/红包"等诱导文案 |
| 此前造成过危险误点 | 该控件签名的危险失误计数 > 0 |
| 用户标记为误操作 | 用户在该规则上点过"误操作" |
| 不可点击 | 控件及其 3 层内祖先都不可点击 |
| 不可见 | `isVisibleToUser` 为 false |
| 体积不合理 | 控件占屏超过 62%，不可能是按钮 |

### 点击后验证

计划书第八节强调"点击成功 ≠ 广告跳过成功"。验证器按以下顺序判断，**先查危险再看成功**：

1. 前台变成浏览器 / 应用商店 / 安装器 / 支付页 / 权限弹窗 → **疑似误点**
2. 新窗口出现支付或密码类控件 → **疑似误点**
3. 前台包名变成其他应用 → **疑似误点**
4. Activity 或页面内容发生变化 → **跳过成功**
5. 等待 600ms 后毫无变化 → **无变化**
6. 采样过早 → **无法判断**（不计为失败）

第 1 步的顺序是刻意的：如果一次点击既让广告消失又打开了支付页，那是误点，不是成功。

### 限流与断路器

- 两次自动点击最小间隔：默认 1200ms
- 每个应用每分钟最多自动点击：默认 3 次
- 同一窗口内点击后页面无变化，重复 2 次后停止点击该窗口
- 危险误点达到上限（默认 2 次）后**暂停该应用的自动操作**，需用户手动恢复

### 不会被滥用

- 不申请按键过滤权限（`flagRequestFilterKeyEvents`）——那能看到密码输入
- 不申请截图与手势权限
- `isAccessibilityTool` 保持 false（见下）

## 隐私设计

无障碍权限是 Android 上最强的权限之一，因此这里的承诺尽量做成**结构性保证**而不是说明文字。

### 发布版本没有网络权限

```
$ aapt2 dump permissions app-release-unsigned.apk
package: io.github.szzgx.smartskip
permission: io.github.szzgx.smartskip.permission.INSPECTOR
permission: io.github.szzgx.smartskip.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='io.github.szzgx.smartskip.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

没有 `INTERNET`。也没有存储、相机、麦克风、联系人、定位、短信权限。

这两个权限都是无害的：`permission.INSPECTOR` 是本应用自己声明的 signature 级权限，
用于保护 Inspector 的抓取触发；`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 是 AndroidX
自动生成的，用于本应用内部注册非导出动态广播接收器。

这意味着**发布版本在系统层面就无法外传它看到的数据**，而不是"我们承诺不上传"。

调试版本才有 `INTERNET`，仅用于研究 WebView 类广告：

```
$ aapt2 dump permissions app-debug.apk
package: io.github.szzgx.smartskip.debug
uses-permission: name='android.permission.INTERNET'
permission: io.github.szzgx.smartskip.permission.INSPECTOR
...
```

### 不保存屏幕上的文字

数据库里**不存储** `nodeText`。只存储文本的**分类**（是否含"跳过"、是否在广告容器内、
resource-id 是否是广告 SDK 的 SkipView）。理由：规则的记忆价值在于"这里是个跳过按钮"，
而不在于"它当时写的是跳过"，而且三个字的标签做哈希也能被穷举反推。

需要展示文字的地方（记录页、检查器）都是**即时从内存读取并显示，不落盘**。

### 输入框根本不会被评估

密码框、输入框、勾选项在**候选阶段**就被排除，不会进入评分流程。这比在评分后否决更彻底：
系统不会对这类控件做任何"推理"。

### 其他

- 数据库已排除云备份与设备迁移（`data_extraction_rules.xml`）
- 只有"界面检查器"会在内存中保留屏幕文字，用于调试，界面上提供"清空"按钮
- 不保存短信、验证码、银行卡号、聊天内容

### 关于 `isAccessibilityTool`

Android 16 起，未声明自己是"无障碍工具"的服务**无法读取**被标记为
`accessibilityDataSensitive` 的视图（登录页、支付页等）。
（[Android Developers 博客](https://android-developers.googleblog.com/2025/12/enhancing-android-security-stop-malware.html)）

把 `isAccessibilityTool` 设为 true 可以绕开这个限制，让 SmartSkip 在更多界面上工作。
**本项目选择不这么做**，因为该标志的含义是"本服务用于帮助残障用户使用设备"，而 SmartSkip
不是。为了看到更多屏幕内容而谎报这个标志，是不诚实的，Google Play 也会拒绝这类应用。

**代价是真实存在的**：在 Android 16+ 上，如果某个 App 用了 `accessibilityDataSensitive`，
SmartSkip 就读不到它的控件，那部分开屏广告无法跳过。这是为了不说谎而接受的取舍。

## 构建与安装

### 环境要求

| 项目 | 版本 |
|---|---|
| JDK | 17 或更高（已在 JDK 21 上验证） |
| Android SDK | Platform 37、Build-Tools 37 |
| Gradle | 9.8.0（由 wrapper 提供） |
| AGP | 9.4.1 |
| Kotlin | 2.2.10（由 AGP 9 内置 Kotlin 提供） |

`compileSdk = 37`、`targetSdk = 36`、`minSdk = 29`（Android 10）。

### 构建

```bash
# 指向你的 SDK（该文件已在 .gitignore 中，不会提交）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 调试包（含 INTERNET 权限，用于调试 WebView 广告）
./gradlew :app:assembleDebug

# 发布包（无网络权限；R8 混淆 + 资源压缩）
./gradlew :app:assembleRelease
```

### 发布签名

仓库中**不含任何签名密钥**。签名信息从以下来源读取，按优先级：

1. 环境变量：`SMARTSKIP_KEYSTORE_FILE`、`SMARTSKIP_KEYSTORE_PASSWORD`、
   `SMARTSKIP_KEY_ALIAS`、`SMARTSKIP_KEY_PASSWORD`
2. 仓库根目录下未纳入版本控制的 `keystore.properties`

两者都没有时，发布包保持**未签名**——首次克隆仍然可以构建成功。

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 代理

如果构建环境需要 HTTP 代理，JVM 不会读取 Windows 的代理设置，需要显式配置。
本项目在 `~/.gradle/gradle.properties`（**机器级，不进仓库**）中配置：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

## 使用流程

1. 安装并打开 SmartSkip
2. 在"状态"页点"去开启"，在系统设置里授予无障碍权限
3. **先不要打开自动跳过**。保持总开关关闭，正常使用手机
4. 过一段时间回到"记录"页，看看 SmartSkip 都判断了什么、依据是什么
5. 如果判断合理，打开总开关
6. 首次遇到的应用只会被观察，不会自动点击。在"规则"页对正确的规则点"确认可用"，下次就会自动跳过
7. 如果发现误点：立刻关闭总开关，在"规则"页把对应规则标记为"误操作"

## 调试

### 界面检查器

"检查器"页显示当前前台应用的完整控件树，以及每个"跳过"类控件的完整评分和加减分项。

要看某个应用的开屏广告：
1. 让那个广告出现在屏幕上
2. 回到 SmartSkip 的检查器页
3. 点"重新抓取（含评分）"

也可以从命令行触发：

```bash
adb shell am broadcast \
  -a io.github.szzgx.smartskip.action.REQUEST_SCORED_DUMP \
  -n io.github.szzgx.smartskip.debug/io.github.szzgx.smartskip.accessibility.InspectorCommandReceiver
```

该广播受 signature 级权限保护，第三方应用无法触发——否则任何应用都能让 SmartSkip 去读当前屏幕。

### 日志

```bash
adb logcat -s SmartSkip SmartSkip/Svc SmartSkip/Inspector
```

服务会记录每一次判断结果、每一次点击、每一次验证结果。

## 项目结构

```
app/src/main/java/io/github/szzgx/smartskip/
├── SmartSkipApplication.kt        进程入口
├── ServiceContainer.kt            手工装配的对象图
│
├── analysis/                      ★ 纯 Kotlin，不依赖 Android，可单元测试
│   ├── Feature.kt                 评分项、置信度区间、模式、否决原因
│   ├── CandidateContext.kt        候选控件的全部可观察事实
│   ├── WindowContext.kt           窗口级事实
│   ├── Stage.kt                   管线阶段与点击结果枚举
│   ├── text/                      归一化 + 关键词 + 文本证据
│   ├── position/                  位置分类
│   ├── time/                      时间特征
│   ├── structure/                 UI 结构特征
│   ├── activity/                  Activity 特征
│   ├── history/                   历史规则证据
│   ├── risk/                      风险特征与窗口级探测
│   └── assessment/                ★ 评分引擎与积分表
│
├── decision/DecisionEngine.kt     阈值计算与决策门（纯 Kotlin）
│
├── safety/                        ★ 纯 Kotlin
│   ├── OutcomeClassifier.kt       危险目的地识别
│   ├── ClickVerifier.kt           点击后行为验证
│   └── RateLimiter.kt             限流器与断路器
│
├── accessibility/                 唯一接触 Android 无障碍 API 的地方
│   ├── SmartSkipAccessibilityService.kt
│   ├── AccessibilityTreeCollector.kt   树 → 不可变快照
│   ├── NodeSummary.kt             不可变节点快照
│   ├── CandidateCollector.kt      候选筛选 + 上下文构造
│   ├── ServiceStatus.kt           UI 读取的服务状态
│   ├── UiDumpProvider.kt          Inspector 状态
│   └── InspectorCommand.kt        Inspector 抓取触发
│
├── data/
│   ├── db/                        Room 实体、DAO、数据库
│   ├── prefs/                     DataStore 偏好
│   ├── rules/RuleStore.kt         规则缓存与置信度更新
│   ├── packages/SafetyRepository.kt  每应用安全状态
│   └── events/EventRecorder.kt    评估记录
│
└── ui/                            Jetpack Compose
    ├── MainActivity.kt
    ├── SmartSkipApp.kt            底部导航
    ├── theme/Theme.kt
    ├── components/Common.kt
    └── screens/                   状态 / 记录 / 规则 / 检查器 / 设置
```

**架构上最要紧的一条**：`analysis/`、`decision/`、`safety/` 三个包完全不依赖 Android 类型。
所有判断逻辑都在其中，因此可以用普通 JVM 单元测试验证——不需要模拟器。
`accessibility/` 只负责收集事实和执行决定，**不做任何判断**。

这个分离是刻意的：这个文件里的错误，会变成用户手机上一次真实的误点。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

94 个 JVM 单元测试，不需要设备。覆盖：

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `AssessmentScorerTest` | 30 | 评分、否决、风险特征、解释完整性、阈值边界、伪跳过 |
| `TextNormalizerTest` | 17 | 文本归一化、规避手法、关键词匹配、倒计时解析 |
| `ClickVerifierTest` | 14 | 点击后验证、危险优先于成功、结果语义 |
| `DecisionEngineTest` | 10 | 阈值运算、门槛顺序、信任度顺序、学习进程 |
| `PositionAnalyzerTest` | 8 | 位置分类与角落亲和度 |
| `CircuitBreakerTest` | 8 | 断路器、暂停与恢复 |
| `RateLimiterTest` | 7 | 限流器（注入时钟） |

### 真机测试：对抗性测试靶

单元测试证明逻辑自洽，但证明不了"它在真机上按下了正确的东西"。真机上唯一能跑通点击链路的资产
是一个 debug 专属的**对抗性测试靶**（14 个精心构造的伪装广告布局），每个场景都事先声明了
"正确行为"是什么：

```powershell
pwsh tools/run-harness.ps1           # 只观察：评分与否决
pwsh tools/run-harness.ps1 -Act      # 激活：点击链路，规则已确认
```

判据是测试靶**自己**记录的点击，而不是引擎的判决——一个声称点了但屏幕没收到点击的引擎，
只有独立记录才能抓住。

为什么需要它：真实广告**测不了点击链路**。实测中唯一抓到的一条真实开屏广告，它的无障碍树里
**没有任何跳过控件**（只有优惠、自动关闭倒计时和"开心收下"），而真实广告也没有理由去
*伪造*一个它可以直接隐藏的控件——伪跳过这个核心威胁只能靠人工构造出来。

真机验证过与未验证过的内容，以及七个曾经产出过"自信的错误结论"的测试陷阱（`am force-stop`
会摘掉无障碍服务等），都记在 [docs/TESTING.md](docs/TESTING.md)。脚本说明见 [tools/README.md](tools/README.md)。

其中几个测试是针对真实问题的回归防线：

- 全角字符、零宽字符、数学粗体字母的规避
- `"跳 过"` / `"Skip  Ad"` 这类插入空格的规避
- 硬性否决不能被高分推翻
- 首次遇到的页面在任何模式下都不能被自动点击
- 一次既跳过广告又打开支付页的点击，必须被记录为误点
- 微信 / 支付宝在前台不能被当成危险目的地（否则会持续误报）
- 占屏 30% 的居中块必须被识别为广告素材

## 已知限制

诚实列出当前版本做不到的事：

### 识别能力

- **Canvas / 自绘 UI / 图片按钮里的"跳过"**：无障碍树里没有文字节点时无法识别。
  这是计划书第十九节"方向 1：视觉识别（本地 OCR）"要解决的问题，本版本**未实现**。
- **Accessibility Tree 不完整的应用**：部分广告 SDK 故意不暴露控件信息。
- **Android 16+ 上被标记为 `accessibilityDataSensitive` 的界面**：见上文，读不到。
- **WebView 内部广告**：取决于 WebView 是否向无障碍层暴露内容，经常只有模糊的容器节点。
- **游戏类 App**：多为自绘界面，无障碍树信息极少。

### 未经实测

- **评分权重是基于推理设定的初值**，没有在真实广告样本上做过统计校准。
  首次使用时请先观察，再决定是否开启自动跳过。
- **没有真机实测数据**，因此计划书第十五节的四个指标（跳过成功率、漏跳率、
  误点击率、危险误点击率）目前**无法给出数值**。
- 规则学习的行为只有单元测试覆盖，尚未在多应用、多广告 SDK 的真实场景下验证。

### 功能缺口

- 不支持导出/导入规则包（计划书第十九节"方向 3：规则共享"）
- 没有通知栏提示（设置里保留了开关，但尚未接入通知渠道）
- 没有针对每个应用的独立设置界面（目前是"仅观察 / 排除"两个列表）

### 平台限制

- 无法在应用内直接开关无障碍权限，必须跳转系统设置（Android 的刻意设计）
- 部分国产 ROM 会限制后台服务，可能需要手动加入电池优化白名单
- 未做多用户 / 工作资料（Work Profile）适配

## 与计划书的差异

实现过程中有几处刻意偏离计划书，理由如下：

### 1. 数据库不存 `nodeText`

计划书第十二节要求存 `nodeText`，第十八节又要求"只保存广告识别所需的最小特征"、
"文本特征的哈希/类别"。这两条无法同时满足，**以第十八节为准**：只存文本分类，不存原文。

理由：规则的记忆价值在于"这里是跳过按钮"，而不在于当时的文案；而短字符串的哈希可以被
穷举反推，所以哈希也不够安全。

### 2. 首次遇到的页面阈值设为 101

计划书通过第七、九、十节描述了"谨慎模式"和"用户确认后才自动执行"，但表述为倾向而非硬规则。
本实现把它做成**超过评分上限的硬门槛**，因为有限的惩罚值挡不住高分的伪造按钮。

### 3. `isAccessibilityTool` 保持 false

计划书第十七节写"能不用的权限坚决不用"。本实现把这个原则延伸到了这个标志上——
它不申请额外权限，但会让服务获得读取受保护数据的能力。详见上文。

### 4. 危险目的地判定不能单独使用

`OutcomeClassifier.classify()` 只在**一次点击真的导致了画面跳转**时才有意义。
如果用户自己打开了 Chrome，"前台是 Chrome"并不代表误点。这一点写在代码注释里，
并且去掉了早期版本中一个容易被误用的 `isKnownDangerousPackage()` 方法。

## 后续方向

按优先级：

1. **真机实测与权重校准** —— 目前最关键的一步。需要在多个真实 App 上收集数据，
   调整积分表，并给出计划书第十五节的四个指标
2. **通知栏反馈** —— 每次自动跳过后给出可撤销的提示，让用户能立刻发现误点
3. **规则导出 / 导入** —— 计划书方向 3
4. **本地 OCR 兜底** —— 计划书方向 1，处理 Canvas / 自绘广告
5. **每应用独立设置** —— 覆盖模式、阈值、是否自动
6. **Phase 5 异常处理加固** —— 多层广告、嵌套广告、广告素材中的"跳过"文字

## 许可

[MIT](LICENSE)

---

**再次强调**：这是一个能自动点击你屏幕上按钮的工具。请从小范围开始用，保持总开关关闭
观察一段时间，确认它的判断符合你的预期之后再启用自动跳过。

# 锁屏时钟隐藏 · LegionClockHider

> **仓库**：[github.com/yyz200/LegionClockHider](https://github.com/yyz200/LegionClockHider)
> · **下载**：[Releases](https://github.com/yyz200/LegionClockHider/releases/latest)（已签名 APK，直接安装）

> ## ⚠️ 本项目由 AI 编写，且**尚未在真机上验证**
>
> 代码、构建脚本与文档全部由 AI 助手（DeepSeek Harness）生成。人类参与者只提供了需求
> （机型、系统版本、框架版本、要隐藏的内容）。**开发者手上没有这台设备**，因此：
>
> - 目标类名来自公开的 ZUXOS 逆向资料（见「原理」一节）+ 模块内置的**运行时自动发现**兜底；
> - 逻辑本身自洽、已编译出签名 APK，但**第一次真机效果需要你来验证**；
> - 如果没生效，请按「排错」三步走把日志发回，即可据此出 v1.0.1。
>
> 说人话：**能装、能跑、结构是对着 ZUXOS 的真实类名写的，但请当作"待验证版本"来对待。**

---

## 这是什么

一个 **LSPosed 模块**：把 **联想拯救者 Y700 五代（ZUXOS 2.0.11.043 / Android 16）** 锁屏界面的
**大时钟 / 日期 / 星期**隐藏掉，让锁屏变干净。

| 项目 | 值 |
|---|---|
| 目标机型 / 固件 | 联想拯救者 Y700 五代（Legion Y700 Gen5）· ZUXOS 2.0.11.043 · Android 16 / API 36 |
| 框架 | LSPosed 2.x（版本号 7790 属这一代，走现代 libxposed API） |
| 模块入口 | 现代 libxposed API（`META-INF/xposed/java_init.list`，`targetApiVersion=102`）+ 传统 `assets/xposed_init` 兼容入口 |
| 包名 | `com.legion.clockhider` |
| 作用域 | `com.android.systemui`（系统界面） |
| 状态 | 可安装、可签名、已通过静态验证；**真机效果待验证** |
| 默认 | 总开关**关闭**；打开后默认隐藏「时钟 + 日期 + 星期」 |

## 功能一览

| 功能 | 说明 | 默认 |
|---|---|---|
| **总开关** | 关闭时所有 Hook 立即卸载，并把改过的视图还原 | 关 |
| **整体隐藏（推荐）** | 直接把锁屏时钟容器（ZUI 的 `AnimatableClockViewWithDate`）设为 GONE | 选中 |
| **逐项隐藏** | 时钟 / 日期 / 星期 分别开关；同一行里「9月12日 星期五」可以只删星期保留日期 | 可选 |
| **隐藏农历 / 节气** | 本机锁屏若有农历行，一并隐藏 | 关 |
| **隐藏手段** | `GONE`（不占位置）/ `INVISIBLE`（保留位置，布局不跳） | GONE |
| **自动发现本机类名** | 扫 SystemUI 的 `classes*.dex`，找出本机真实的锁屏时钟类并自动补挂 —— 换固件 / OTA 后仍能自适应 | 开 |
| **激进模式** | 全局监听新视图，凡类名含 `clock` / `withdate` 的都按规则处理（默认方案无效时的保险） | 关 |
| **顺带隐藏状态栏时钟** | 注意是全局的：解锁后状态栏时钟也会消失 | 关 |
| **手动补充类名** | 诊断出真实类名后填进 App，立刻按它挂载 | 空 |
| **视图树抓取 / 诊断** | App 里一键抓取锁屏视图结构 + 一键取模块日志（root），排错用 | — |

## 原理（逆向得到的关键结论）

### 1. ZUI/ZUXOS 的锁屏时钟是哪个类

来自另一个**开源 ZUXOS 专用模块** [ZTool（ZUX-ZTool）](https://github.com/qwqawa64/ZUX-ZTool) 的
`hook/modules/systemui/keyguard/LockScreenClockColorHook.kt` 注释：

> The ZUI lock screen clock is rendered by SystemUI's **`AnimatableClockViewWithDate`**:
> `onAttachedToWindow` 与一个 ContentObserver 读取 `Settings.System` 的 `lock_screen_clock_color`
> 存进 `mCustomizedColor`，然后调用 `updateColor(int)`，**把颜色写到时钟和日期两个 TextView 上**。

所以本模块的主目标是：

```
com.android.systemui.shared.clocks.AnimatableClockViewWithDate
```

它本身就是「时钟 + 日期」的容器，直接整体隐藏即可达成需求；另外附了
`AnimatableClockView`、`KeyguardClockSwitch`、`KeyguardStatusView` 等 AOSP 候选类兜底。

### 2. 三层识别，层层兜底

```
第一层：已知类名直挂        → AnimatableClockViewWithDate 等（精确、零误伤）
第二层：本机 dex 自动挖掘    → 扫 SystemUI.apk 的 classes*.dex，找名字含 clock/withdate
                              且属于 keyguard/lockscreen/clocks 命名空间的 View 类，自动补挂
                              （应对固件改名，结果同时写到 mined-classes.txt 供诊断）
第三层：激进模式（默认关）    → 全局 View.onAttachedToWindow，类名含 clock 的新视图一律处理
```

### 3. 隐藏动作不靠猜 id，而是看"内容"

厂固件的控件 id 和层级经常改，但锁屏上「9:41」「9月12日」「星期五」这些**文本形态**是稳定的，
所以 `ViewRules` 用正则识别：

```
时钟:  \d{1,2}:\d{2}(:\d{2})?          日期:  \d{1,2}月\d{1,2}日 / 2025-09-12 / 2025年9月12日
星期:  (星期|周|礼拜)[一二三四五六日天]   农历:  农历/腊月/廿三/初七…
```

- 视图内容**全部**要隐藏 → 设 `GONE`/`INVISIBLE`；
- 只隐藏**一部分**（例如只删星期、保留日期）→ 把文本里对应片段正则删掉，**原文本会被记下来，关掉开关即还原**；
- 同时按 `类名 / 资源 id` 做辅助判断（含 clock/date/week/lunar 的类名或 id 名加分）。

### 4. 为什么能"实时生效"、也能"关得干净"

- 配置走三条通道（libxposed 远程偏好 / ContentProvider / root 兜底文件）；
- 目标类自身的"刷新类方法" + 框架 `View` 生命周期（`onAttachedToWindow` / `onVisibilityChanged`）
  都会被挂上，锁屏一出现就处理，不必等下一次分钟跳变；
- 另有 **500ms 周期复检**：万一系统把可见性或文本改回去，会被立刻改回来；
- 关掉总开关 → 卸载全部 Hook + 还原可见性与原文本。

所有 Hook 都使用框架的 **PROTECTIVE 异常模式**，模块内异常不会影响 SystemUI。

## 安装

1. 安装 `dist/LegionClockHider-v1.0.0.apk`
2. 打开 **LSPosed 管理器** → 模块 → 启用「锁屏时钟隐藏」→ 作用域勾选 **系统界面（`com.android.systemui`）**
3. 打开模块 App → 打开**总开关**（此时会弹出 Root 授权，用于写兜底配置与读日志）
4. 息屏再亮屏回到锁屏，或在本 App 里点「**重启 SystemUI**」

> 配置改动**不需要重启手机**；只有"改了作用域 / 补装模块"才需要重启 SystemUI 或手机。
> APK 为自签名（`legionclockhider.keystore` 随工程保留，请不要丢，否则后续版本无法覆盖安装）。

## 配置

既可在 App 界面调整，也可直接编辑**兜底配置文件**（三处任一，改动即时生效）：

```
/data/adb/lspd/modules/0/com.legion.clockhider/config.properties   # App 用 root 写入（首选）
/data/adb/legionclockhider.conf
/sdcard/Download/legionclockhider.conf                             # 无需 root，文件管理器即可改
```

```properties
enabled=1                 # 总开关
hide_mode=0               # 0=整体隐藏时钟容器（推荐） 1=逐项隐藏
hide_clock=1              # 逐项：时钟
hide_date=1               # 逐项：日期
hide_weekday=1            # 逐项：星期
hide_lunar=0              # 逐项：农历/节气
hide_visibility=0         # 0=GONE 1=INVISIBLE
auto_discover=1           # 自动挖掘本机真实类名
aggressive=0              # 激进模式
hide_statusbar_clock=0    # 顺带隐藏状态栏时钟（全局）
extra_classes=            # 手动补充类名，逗号分隔
dump_token=0              # App"抓取视图树"用的触发令牌
```

配置读取优先级：`ContentProvider` → 框架远程偏好 → 兜底文件 → 传统 `XSharedPreferences`。

## 排错（三步）

1. App 里点「**抓取锁屏视图树**」→ **息屏再亮屏**回到锁屏停 2 秒；
2. App 里点「**查看模块日志**」（会请求 root）；
3. 把日志里的这几段发回：
   - `[C] Hook 安装与命中情况` —— 目标类是否存在、是否挂上、是否命中；
   - `[D] 本机挖掘到的候选类名` —— 你机器上真实的锁屏时钟类名；
   - `[H] 本机 SystemUI 里真实存在的「时钟」类` —— 直接列 SystemUI.dex 里所有含 `Clock` 的类。

拿到 `[D]/[H]` 就能确定真实类名，填进 App 的「**手动补充类名**」立即生效（不用等新版本）。

## 目录结构

```
LegionClockHider/
├── app/src/main/java/com/legion/clockhider/
│   ├── ModernEntry.java      # 现代 libxposed 入口（java_init.list 声明）
│   ├── LegacyEntry.java      # 传统 de.robv 入口（旧框架兜底）
│   ├── ClockHider.java       # 引擎：挂载目标类 / 生命周期 / 周期复检 / 视图树抓取
│   ├── ViewRules.java        # 识别（文本+类名+id）与隐藏/还原规则
│   ├── TargetScanner.java    # 扫 SystemUI dex 找本机真实类名
│   ├── Hooker.java           # 框架适配接口（统一现代/传统 API）
│   ├── ModernHooker.java / LegacyHooker.java
│   ├── ConfigStore.java      # 四通道配置读取
│   ├── ConfigWriter.java     # root 写三处兜底配置
│   ├── RemotePrefsBridge.java# libxposed 远程偏好桥（App→SystemUI 实时下发）
│   ├── PrefsProvider.java    # 配置跨进程只读通道
│   ├── Diag.java             # 一键诊断命令集
│   ├── LogFileProvider.java  # 日志导出/分享
│   ├── App.java              # Application
│   └── ui/SettingsActivity.java
├── app/src/main/resources/META-INF/xposed/   # 现代入口声明（java_init.list / scope.list / module.prop）
├── app/src/main/assets/                      # 传统入口声明（xposed_init / module.prop）
├── dist/                                     # 构建产物 APK
└── tools/                                    # 免 Gradle 构建脚本
```

## 自行构建

附带**免 Gradle** 的构建脚本（`aapt2 + javac + d8 + zipalign + apksigner`）：

```powershell
powershell -File tools/build_apk.ps1
```

依赖（本工作区已具备）：JDK 17、Android SDK（`build-tools;36.0.0` + `platforms;android-36`）、
`tools/downloads/` 下的 Xposed 传统 API 82 与 libxposed api/service 102 的 jar。
产物输出到 `dist/`。用 Android Studio 打开亦可（`app/build.gradle` 已配置好，不含远程依赖）。

## 已知限制（诚实清单）

- **未在真机上验证过**：目标类名取自公开逆向资料 + 运行时自动发现，第一次上机可能需要微调；
- **只处理锁屏**：AOD（息屏显示）的时钟是另一套渲染路径，本版本未介入；
- **需要 Root**：LSPosed 本身需要；另外「写兜底配置 / 读日志 / 重启 SystemUI」用了 `su`，
  不给 Root 也能用（配置改走框架远程偏好通道），只是无法读日志；
- **状态栏时钟开关是全局的**：打开后解锁状态也会没有状态栏时钟；
- **"整体隐藏"可能留下空白**：若你的固件把时钟放在固定高度的容器里，锁屏上部可能空一块，
  这时改用「逐项隐藏」或 `INVISIBLE` 观感更好；
- 其它 ZUXOS/ZUI 机型/版本**未验证**（`ZUI` 上部分能力也可能不同）。

## 参考 / 致谢

- [ZTool（ZUX-ZTool）](https://github.com/qwqawa64/ZUX-ZTool) —— ZUXOS 逆向资料与
  `AnimatableClockViewWithDate` 这一关键结论的来源（本模块**没有**使用其任何代码）
- [LSPosed](https://github.com/LSPosed/LSPosed) / [libxposed](https://github.com/libxposed) —— 框架与 API
- 工程骨架（现代 libxposed 双入口、四通道配置、诊断/导出、免 Gradle 构建脚本）沿用本机
  既有的 `NotificationSinking` 工程，避免重复踩坑

## 许可

MIT License，详见 [LICENSE](LICENSE)。

---

### AI 生成声明

本工程的全部源代码、构建脚本与文档均由 AI 生成；人类参与者提供需求与真机测试反馈。
AI 可能产生错误或过时信息，使用前请自行审阅。因使用本模块导致的任何后果
（包括但不限于系统异常、数据丢失、保修问题）由使用者自行承担。

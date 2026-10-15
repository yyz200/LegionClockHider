# 更新日志 · LegionClockHider

## v1.0.0（首个版本）

### 功能
- 隐藏 ZUXOS 锁屏界面的**大时钟 / 日期 / 星期**（默认三项全隐藏）；
- 两种隐藏方式：**整体隐藏**时钟容器（默认，最可靠）/ **逐项隐藏**（时钟、日期、星期、农历分别开关，
  同一行文本可只删星期保留日期）；
- 隐藏手段可选 `GONE`（不占位）或 `INVISIBLE`（保留位置）；
- **自动发现**：扫 SystemUI dex 找出本机真实的锁屏时钟类并自动挂载（应对固件改名 / OTA）；
- **激进模式**：全局监听新视图，凡类名含 `clock`/`withdate` 都按规则处理（兜底）；
- 可选**顺带隐藏状态栏时钟**；
- **手动补充类名**：诊断出真实类名后填进 App 立即生效，无需等新版本。

### 诊断
- App 内一键「抓取锁屏视图树」（`VIEWTREE` 前缀写入日志，含祖先链、资源 id、内容类型、文本）；
- App 内一键「查看模块日志 / 精简日志 / 导出日志到 txt」（root 读取 logcat，分段超时互不影响）；
- 内置 `[H]` 段：直接解包 SystemUI.dex 列出本机所有名字含 `Clock` 的类，不依赖模块自身逻辑。

### 工程
- 现代 libxposed API（`targetApiVersion=102`）为主入口，`assets/xposed_init` 传统入口兜底；
- 四通道配置下发：ContentProvider / libxposed 远程偏好 / root 兜底文件 / XSharedPreferences；
- 免 Gradle 构建脚本（`aapt2 + javac + d8 + zipalign + apksigner`），离线可出包；
- 所有 Hook 使用 PROTECTIVE 异常模式，异常不会影响 SystemUI；总开关关闭时卸载 Hook 并还原视图。

### 已知问题
- **未在真机上验证**：目标类名来自公开逆向资料（ZTool 对 ZUXOS 1.5 的注释）与运行时自动发现；
- AOD（息屏显示）的时钟未处理；
- 「整体隐藏」在某些固件上可能在锁屏上部留下空白（可改用逐项隐藏或 INVISIBLE）。

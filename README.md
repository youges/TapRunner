# TapRunner

让重复操作更轻松的 Android 连点器。支持 Android 11–16，无广告、不申请互联网权限，脚本、图像模板与日志留在本机。

**首个公开版本：0.5.1**

[下载正式安装包](https://github.com/youges/TapRunner/releases/latest) · [中文使用说明](docs/USER_GUIDE.md) · [版本说明](docs/RELEASE_NOTES_0.5.1.md) · [测试与已知边界](docs/TESTING.md)

## 功能

- 单点与多点点击、长按、直线滑动、文字输入、系统按键和启动应用。
- 等待文字/图像、条件分支、有限循环、变量插值、暂停/恢复与随机坐标微调。
- 点击屏幕创建编号点位，拖动微调；紧凑悬浮编辑器支持放大/缩小。
- 脚本重命名、复制、搜索、排序、删除确认，JSON/ZIP 导入导出。
- 白天、暗夜、跟随系统主题，四种预设强调色与自定义颜色。
- 分开提供基础坐标服务与可选高级识别服务，按需启用。

高级条件分支、变量与失败策略目前仍通过 JSON 编辑。完整图形化条件编辑、轨迹录制、批量间隔、完整撤销/重做、OCR 和 Root/Shizuku 尚未实现。

## 快速开始

1. 从 Releases 下载 `taprunner-0.5.1-release.apk` 并安装。
2. 在「设置」开启坐标连点与悬浮窗，按需开启通知及高级识别。
3. 首页选择单点或多点，切到目标应用，悬浮栏点 **＋** 后依次点击位置，再点「完成」。
4. 点击编号设置间隔，拖动编号微调；保存后点 **▶** 执行。
5. 用悬浮栏或通知停止。高级识别模式还支持长按音量下急停。

间隔按点击开始到下一点击开始计算，手势等待完成回调。锁屏、息屏、旋转或运行中服务失效会停止，不自动续跑。

首次安装正式版前，如果已有开发调试版，请先导出脚本。正式版与调试版签名不同，不能直接覆盖；卸载会清除应用数据。

## 构建

使用 JDK 21、Android SDK 36、Gradle 8.13；Android Gradle Plugin 8.13.2，Kotlin 2.1.20。可在 Android Studio 中打开仓库并设置 SDK，或在本机 `local.properties` 中填写 SDK 路径。首次构建需要下载 Gradle 和依赖。

Windows：

```powershell
.\gradlew.bat :core:engine:test :app:assembleDebug :app:lintDebug
```

macOS / Linux：

```sh
./gradlew :core:engine:test :app:assembleDebug :app:lintDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。核心模型与执行引擎为纯 JVM 模块，Android 界面与平台实现位于 app。

正式构建需要自己的四项签名配置：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`，放在本机未提交的 `signing.properties` 中；也可使用 `-Ptaprunner.signingProperties=<本地配置路径>`。`storeFile` 相对路径以工程根目录为基准。缺失或不完整配置会拒绝发布构建，release 显式关闭 debuggable。

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

官方发布密钥与密码不在仓库中。自行签名的构建不能覆盖官方版本；请先导出脚本再换装。

## 验证与反馈

0.5.1 共 78 项核心测试通过，debug/release 构建成功，Android Lint 零错误、零警告。vivo X200s / Android 16 已完成 0.5.0 的 100 次实际点击与 10 次滑动，以及 0.5.1 的安装和连接提示验证。版本间的测试范围与帧统计见 [验证记录](docs/TESTING.md)。

尚未完成多厂商完整矩阵、10,000 次完整到达率和 8 小时连续运行；厂商后台策略仍可能中断服务。请在 Issues 中提供设备、Android 版本、应用版本和复现步骤，发布日志前移除私人内容。

仅用于你有权操作且符合目标应用规则的任务。

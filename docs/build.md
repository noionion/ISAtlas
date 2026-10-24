# 构建指南

ISAtlas 使用 Kotlin 与 Jetpack Compose，应用最低支持 Android 8.0（API 26）。

## 环境准备

| 工具 | 版本 |
| --- | --- |
| JDK | 21 |
| Android SDK Platform | 35 |
| Android SDK Build Tools | 35.0.0 |
| Gradle | 8.11.1，由仓库内的 Wrapper 提供 |

通过 Android Studio 打开仓库，安装所需 SDK。在项目根目录的 `local.properties` 中设置本机 SDK 路径，例如：

```properties
sdk.dir=C:/Users/your-name/AppData/Local/Android/Sdk
```

设置 `JAVA_HOME` 指向 JDK 21。`local.properties`、构建输出与本地工具缓存不纳入版本控制。

## 构建 APK

在项目根目录执行：

```powershell
./gradlew.bat :app:assembleDebug
```

macOS / Linux 使用对应的 Wrapper：

```sh
./gradlew :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。这是供本地安装的调试包，正式发布需另行配置签名。

构建所需的离线 CSR JSON 和测试样本已纳入仓库，普通构建无需初始化上游子模块。首次构建需要下载 Gradle 与依赖；本机缓存齐全后可使用 `--offline`。

## 安装到手机

在手机上启用 USB 调试，连接电脑并授权后执行：

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

连接多台设备时，通过 `adb -s <设备序列号> install -r ...` 指定安装目标。手机若显示安装确认，请允许本次安装。

## 开发验证

核心逻辑与 Android 静态检查：

```powershell
./gradlew.bat :core:test :app:lintDebug
```

实体设备或模拟器的交互测试：

```powershell
./gradlew.bat :app:connectedDebugAndroidTest
```

数据导入与存储测试使用 Python 3.10+ 标准库：

```powershell
python -m unittest discover -s tools -p "test_*csr*.py"
```

其中手册导入测试需要先初始化固定版本的上游子模块：

```powershell
git submodule update --init upstream/riscv-isa-manual
```

数据库再生成和复核步骤见[数据来源与转换](data-import.md)。

## 项目结构与贡献

| 目录 | 内容 |
| --- | --- |
| `app/` | Android 界面、应用状态与离线资产 |
| `core/` | 数据模型、校验、搜索与位布局逻辑 |
| `tools/` | 数据存储、手册转换、字段说明与复核工具 |
| `upstream/` | 固定提交的 ISA 手册子模块 |
| `docs/` | 构建、数据维护与覆盖范围说明 |

提交信息使用 Angular 风格的 `type(scope): summary`，例如 `docs(readme): simplify product introduction`。修改数据时同步保留来源、版本与许可，并参照[字段复核说明](field-review.md)检查覆盖边界。

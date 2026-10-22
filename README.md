# ISAtlas

离线架构速查 Android 应用原型，使用 Kotlin + Jetpack Compose，支持 Android 8.0 / API 26 及以上版本。

## 功能

- 搜索 CSR 名称、编号、字段和描述，按类别、特权态及 CSR 编号排列。
- 展示寄存器位布局，支持 RV32/RV64、横竖屏适配和拖拽选域。
- 右侧筛选支持位宽、访问权限、扩展及数据来源；横屏使用双栏布局。
- 从详情返回时保留列表位置。
- 从独立 CSR JSON 文件加载离线目录，校验身份、字段及位布局。

## 数据审核状态

当前架构数据及其来源尚待重新确认和审核，未纳入版本控制。临时文档、截图、来源导入配置和测试数据同样仅保留在本地。

代码仓库不包含可直接运行的离线数据库。应用运行需要本地提供以下 assets：

- `riscv-catalog.json`：目录元数据及 `files` 文件名索引。
- `csr/*.json`：每个文件存放一个完整的 CSR 定义。
- `march-extensions.json`：扩展选项和前置依赖。
- `THIRD_PARTY_NOTICES.txt`：与本地数据对应的第三方许可声明。

数据相关测试还依赖本地测试样本。缺少这些文件时，可编译应用，但运行和数据相关测试需要先补齐经过审核的数据。

## 构建

需要 JDK 21、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 使用 8.11.1。

通过 Android Studio 打开项目，或执行：

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug
```

在本地 `local.properties` 中配置 `sdk.dir`，该文件不提交。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

补齐本地数据后执行测试：

```powershell
./gradlew.bat :core:test :app:connectedDebugAndroidTest
```

## 结构

```text
app/       Android 界面、应用状态和偏好设置
core/      数据模型、校验、搜索、位布局和单元测试
gradle/    Gradle Wrapper
tools/     通用 CSR 文件存储与去重工具
```

Git 提交使用 Angular 风格的 `type(scope): summary`，例如 `feat(app): add register search`。

# ISAtlas

离线架构速查 Android 应用原型，当前应用版本 0.2.0，使用 Kotlin + Jetpack Compose，支持 Android 8.0 / API 26 及以上版本。

## 功能

- 搜索 CSR 名称、编号、字段和描述，按类别、特权态及 CSR 编号排列。
- 展示寄存器位布局，支持 RV32/RV64、横竖屏适配和拖拽选域。
- 右侧筛选支持位宽、访问权限、扩展及数据来源；横屏使用双栏布局。
- 从详情返回时保留列表位置。
- 从独立 CSR JSON 文件加载离线目录，校验身份、字段及位布局。

## 离线数据

仓库包含 381 个官方 ISA 手册来源 CSR 和 1 个保留的 T-head CSR，共 1,571 个字段。手册来源的 1,570 个字段均有中英文摘要，其中 1,327 个带枚举说明；应用默认显示中文，英文数据供后续 i18n 使用。

数据位于 `app/src/main/assets/`，构建和运行无需下载数据库：

- `riscv-catalog.json`：目录元数据及 `files` 文件名索引。
- `csr/*.json`：每个文件存放一个完整的 CSR 定义。
- `march-extensions.json`：扩展选项和前置依赖。
- `THIRD_PARTY_NOTICES.txt`：第三方署名、修改说明及许可证。

官方手册通过 `upstream/riscv-isa-manual` 子模块固定到发布标签 `20250508`。11 个调试相关 CSR 的具体定义属于外部规范，仍为仅地址索引；平台相关布局不做推断。来源、转换与复核边界见 [数据导入](docs/data-import.md)、[字段复核](docs/field-review.md) 和 [数据格式](docs/data-format.md)。

仅在需要再生成数据时初始化子模块，并运行 Python 3.10+ 标准库脚本：

```powershell
git submodule update --init upstream/riscv-isa-manual
python tools/import_isa_manual.py
python tools/review_manual_fields.py
```

来源版本、保留厂商文件指纹及许可证路径记录在 `tools/data-sources.lock.json`。生成的复核报告保存在 `docs/data/`；报告、临时截图和构建缓存不提交。

## 构建

需要 JDK 21、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 使用 8.11.1。

通过 Android Studio 打开项目，或执行：

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug
```

在本地 `local.properties` 中配置 `sdk.dir`，该文件不提交。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

执行测试：

```powershell
./gradlew.bat :core:test :app:connectedDebugAndroidTest
python -m unittest discover -s tools -p "test_*csr*.py"
```

## 结构

```text
app/       Android 界面、应用状态和偏好设置
core/      数据模型、校验、搜索、位布局和单元测试
gradle/    Gradle Wrapper
tools/     CSR 存储、官方手册转换、字段说明、复核和来源锁定
upstream/  固定版本的官方 ISA 手册子模块
docs/      数据格式、来源转换与字段复核说明
```

Git 提交使用 Angular 风格的 `type(scope): summary`，例如 `feat(app): add register search`。

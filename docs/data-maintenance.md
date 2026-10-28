# 本地数据维护与内置基线

可编辑 JSON、提取脚本、规范 submodule、来源锁定、公开模板及数据审核统一维护在 [ISAtlas-data](https://github.com/noionion/ISAtlas-data)。应用仓库保留运行时 Kotlin 模型/校验器、Manual 固定 ZIP、少量回归夹具和 `data/builtin-data.lock.json`。不在两个仓库分别修改同一份数据源。

## 配置本机目录

两个仓库可放在同一父目录。使用 PowerShell 7，在应用根目录创建被 Git 忽略的 `data.local.properties`：

```properties
data.dir=../ISAtlas-data
```

也可给下面的脚本传入 `-DataDirectory`。配置不写进 APK、文档或锁文件；普通 Gradle 构建不读取该文件，也不自动访问数据仓库。

## 生成并审核数据

在数据仓库中运行：

```powershell
git submodule update --init --recursive
python tools/build_data.py
python tools/check_data.py
```

Python 3.10+ 标准库即可运行。生成物在 `dist/riscv/`，测试样本在 `dist/test-fixtures/`，全量审核报告在 `reports/`；均不提交。可编辑源文件和固定上游提交需要提交到数据仓库。

## 验证候选包

回到应用仓库，显式执行：

```powershell
./tools/Test-Data.ps1
```

该命令从配置的数据仓库读取候选产物，通过应用实际 Kotlin 解析/校验器检查全部八种包组合，再执行核心测试、Android 静态检查并构建调试 APK。候选数据仅通过本次 Gradle 参数 `-Pisatlas.dataDir` 接入调试构建和核心测试，**不修改已追踪的内置资产或锁文件**。后续不带该参数的构建自动恢复使用已锁定基线；Release 的资产来源始终为已追踪的基线。

脚本支持 `-Offline`、`-GradleExecutable` 和 `-GradleUserHome`，可使用本机已有的离线依赖与工具目录；这些路径不保存到公开文件。

## 更新内置基线

选定并验证数据版本后，显式同步：

```powershell
./tools/Sync-Data.ps1
./tools/Verify-BuiltinData.ps1
```

同步前先校验完整候选官方包，再仅将 Manual ZIP 和筛选后的索引复制到正式资产；AIA/Debug/SPMP ZIP 与完整索引只复制到 Android/Core 共用测试夹具，绝不加入主 APK。同步扩展依赖、声明/许可和回归夹具，生成锁文件；每个文件都核对 SHA-256。锁文件记录公开数据仓库地址、提交（如存在）、工作树状态、上游修订和逐文件指纹，不记录本机路径。

迁移初始状态尚无数据仓库提交，因此锁文件 `revision` 可为空、`workingTree=true`；此时 SHA-256 固定的是一个明确的本地候选快照，不表示已发布版本。首次数据提交或发布后，应重新同步并把实际数据提交写入应用锁文件。对外选定基线时优先使用已审核的干净数据提交。

应用提交只包含选定的 ZIP、锁文件与必要测试样本；提取脚本、JSON 修正和规范升级提交到数据仓库。数据修改本身不触发 APK 发版；应用版本更新仍遵循本仓库发布规则。

## 构建与职责边界

普通应用构建和核心测试仅使用本仓库文件：核心指令测试需要的 JSON 从 Manual ZIP 解到构建目录，不读取另一个工作区。公开本地包的两个 ZIP 仅作为 Android 测试夹具，模板源文件留在数据仓库。

这次迁移不实现应用内数据库下载、签名更新索引或独立数据 Release；这些继续属于“数据库独立更新”后续工作。现有离线查询、规范包启停、本地包导入与应用更新不改变。

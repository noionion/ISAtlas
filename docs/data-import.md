# 数据来源与提取

固定来源、提取流程与审核已迁至独立的 [ISAtlas-data 仓库](https://github.com/noionion/ISAtlas-data/blob/dev/docs/data-import.md)，该仓库是可编辑数据与维护文档的唯一来源。

应用仅保留固定版本 ZIP、运行时模型/校验器和 `data/builtin-data.lock.json`。普通构建无需拉取数据仓库或规范子模块。

本地联调与基线同步见[数据维护](data-maintenance.md)。

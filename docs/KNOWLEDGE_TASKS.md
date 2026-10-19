# Wiki 文件存储与导入任务

M1-05 提供文件存储和后台任务基础。文件上传、ZIP 解析、文档版本发布和页面进度展示属于后续 M2/M3 任务，目前尚未提供。

## 文件存储

`WIKI_STORAGE_DIR` 指定服务端原文件存储目录，本地默认为 `./data/wiki/storage`，Compose 使用持久化的 `wiki_storage` 卷。存储服务按随机 UUID 命名文件，保留原始文件名、大小和 SHA-256 供后续文档版本记录使用；拒绝客户端路径和超过 30MB 的文件。当前尚无文件上传接口，因此应用启动不会写入原文件。

## 显式本地导入任务

以下接口均要求登录，写操作还需携带与当前 Cookie 会话对应的 `X-XSRF-TOKEN`。调用导入接口会读取本地 Wiki，并把切片发送至已配置的 Embedding 服务；请只在确实需要重新导入时调用。

| 请求 | 用途 |
| --- | --- |
| `POST /api/knowledge/import/local/tasks` | 创建后台导入任务，返回 HTTP 202 和任务 ID |
| `GET /api/knowledge/tasks/{id}` | 查询 `status`、`stage`、进度、尝试次数及通用错误提示 |
| `POST /api/knowledge/tasks/{id}/retry` | 手动重试失败或中断的本地导入任务，返回 HTTP 202 |

任务记录写入 `ingest_task`。完成后 `status=COMPLETE`、`stage=FINISHED`，`completedItems` 为本次写入切片数。失败时只保存固定错误码和通用提示，不保存模型异常正文。后端重启将遗留的 `PENDING`、`RUNNING` 任务标记为 `INTERRUPTED`，不会自动重试或重新发送 Wiki 文本。

已有的 `POST /api/knowledge/import/local` 仍是同步接口；需要查询后台状态时使用新的任务接口。本阶段还没有逐批 Embedding 的实时进度，相关能力在 M2-04 开发。

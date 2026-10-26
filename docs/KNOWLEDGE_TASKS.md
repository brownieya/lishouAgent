# Wiki 文件存储与导入任务

M1-05 提供文件存储和后台任务基础。M2-01 增加 Markdown/ZIP 上传暂存；文档解析发布、图片说明和页面进度展示仍属于后续 M2/M3 任务。

## 文件存储

`WIKI_STORAGE_DIR` 指定服务端原文件存储目录，本地默认为 `./data/wiki/storage`，Compose 使用持久化的 `wiki_storage` 卷。存储服务按随机 UUID 命名文件，保留原始文件名、大小和 SHA-256 供后续文档版本记录使用；拒绝客户端路径和超过 30MB 的文件。应用启动不会主动上传或写入原文件。

## Markdown/ZIP 上传暂存

`POST /api/knowledge/uploads` 接收 `multipart/form-data`，表单字段名为 `file`。请求需要登录 Cookie 和 `X-XSRF-TOKEN`。成功返回 HTTP 201，包含上传 ID、原文件存储键，以及各 Markdown/图片文件的原始相对路径、类型、存储键、大小和 SHA-256。`GET /api/knowledge/uploads/{id}` 在重启后也能查询同一份清单。

支持单个 UTF-8 `.md`，或含至少一篇 Markdown 的 `.zip`；ZIP 内允许 `.md`、`.png`、`.jpg`、`.jpeg`，保留目录层级作为元数据。原包和解出的文件分别按 UUID 保存，文件不会按 ZIP 给出的路径写入磁盘。上传文件上限 30MB；ZIP 最多 500 个条目、解压后的文件总量最多 100MB。非法路径、重复路径、空 Markdown、图片扩展名与 PNG/JPEG 文件头不符等请求会被拒绝，并清理本次已写入的文件。

上传清单存入 Flyway V2 的 `knowledge_upload` 和 `knowledge_upload_file`。**上传只暂存资料，不调用 Embedding，也不会自动出现在问答检索中。**解析预览见下文；文档版本与向量发布属于后续 M2 任务。

## 上传文档的结构解析预览

登录后调用 `GET /api/knowledge/uploads/{id}/parsed`，按上传 ID 读取已暂存的 Markdown，并返回 `documents` 与 `unreferencedImages`。每篇文档包含标题、原始相对路径、带行号和标题路径的结构块（`HEADING`、`PARAGRAPH`、`TABLE`、`STEP`、`LIST`、`CODE`）以及图片引用。表格、步骤和围栏代码保持为完整块；代码中的图片写法不会被当作实际引用。

Markdown 图片语法和 HTML `img src` 的本地相对路径会以 Markdown 所在目录为基准，在**同一次上传**的图片清单中匹配。引用状态为 `LINKED`、`MISSING`、`EXTERNAL` 或 `INVALID_PATH`；成功关联时给出图片的相对路径和存储键。未被任何文档引用的上传图片列在 `unreferencedImages`。此接口只读、按需解析，不建立文档版本或写入向量；后续导入发布流程会使用解析结果。

## 显式本地导入任务

以下接口均要求登录，写操作还需携带与当前 Cookie 会话对应的 `X-XSRF-TOKEN`。调用导入接口会读取本地 Wiki，并把切片发送至已配置的 Embedding 服务；请只在确实需要重新导入时调用。

| 请求 | 用途 |
| --- | --- |
| `POST /api/knowledge/import/local/tasks` | 创建后台导入任务，返回 HTTP 202 和任务 ID |
| `GET /api/knowledge/tasks/{id}` | 查询 `status`、`stage`、进度、尝试次数及通用错误提示 |
| `POST /api/knowledge/tasks/{id}/retry` | 手动重试失败或中断的本地导入任务，返回 HTTP 202 |

任务记录写入 `ingest_task`。完成后 `status=COMPLETE`、`stage=FINISHED`，`completedItems` 为本次写入切片数。失败时只保存固定错误码和通用提示，不保存模型异常正文。后端重启将遗留的 `PENDING`、`RUNNING` 任务标记为 `INTERRUPTED`，不会自动重试或重新发送 Wiki 文本。

已有的 `POST /api/knowledge/import/local` 仍是同步接口；需要查询后台状态时使用新的任务接口。本阶段还没有逐批 Embedding 的实时进度，相关能力在 M2-04 开发。

# lishouAgent 前端

Vue 3 + Vite 公司 Wiki 聊天页面，从原项目迁移通用聊天界面。

## 当前已实现

- 单一公司知识助手入口；创建新的临时会话。
- POST 流式问答、逐段展示回答、参考资料列表及停止生成。
- 网络断开和后端错误提示；保留已收到的部分回答，只有 `done` 表示生成完成。
- 来源使用文本渲染；回答支持转义后的加粗、行内代码和换行。
- Enter 发送、Shift + Enter 换行、中文输入法确认时不会误发送。

## 后续开发

当前会话 ID 和页面消息仅在页面内维护，刷新会新建会话。数据库历史会话恢复、登录、知识库列表、上传文档、切片编辑和图片预览尚未实现；以仓库根目录 `DEVELOPMENT_PLAN.md` 为准。

## 本地运行

需要 Node.js 22.12+（或受当前 Vite 支持的更高版本）。

```bash
npm ci
npm test
npm run dev
```

开发服务为 `http://localhost:5173`，不会自动打开浏览器。`/api` 代理到 `http://localhost:8124`。可使用不含密钥的 `VITE_API_BASE_URL` 修改 API 前缀；所有模型密钥仅由后端保存，不得放进前端环境变量。

```bash
npm run build
```

产物在 `dist/`。Dockerfile 使用 Node.js 22 构建并由 Nginx 提供静态页面，Nginx 将 `/api/` 转发到 Compose 的 `backend:8124`，关闭代理缓冲以支持 SSE。

## 流式接口约定

`POST /api/ai/chat/stream`，JSON 请求体：

```json
{"message":"这个项目的业务流程是什么？","chatId":"页面生成的会话ID"}
```

响应 `Content-Type: text/event-stream`，事件包括：

| event | data |
| --- | --- |
| `answer` | 回答的增量字符串 |
| `sources` | 来源对象 JSON 数组，支持 `title`、`sourcePath`、`section` 等字段 |
| `done` | 结束标记字符串（允许为空） |
| `error` | 错误提示字符串 |

前端通过 Fetch + AbortController 读取 POST SSE。解析器支持跨网络分块、多行 `data:` 和 CRLF。服务端需用空行结束每个事件，包括 `data:` 为空的 `done` 事件。前端不会将没有 `done` 的 EOF 当成成功。

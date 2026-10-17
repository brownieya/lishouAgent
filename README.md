# lishouAgent

基于公司 Wiki 的内部知识助手。由 `brownie-ai-agent` 迁移，使用 Java 21、Spring Boot 3.5.16、Spring AI Alibaba 1.1.2.0、Spring AI 1.1.2、Vue 3 和 PostgreSQL＋pgvector。

代码仓库：<https://github.com/brownieya/lishouAgent>。

## 当前进度

- 公司知识问答页面、流式回答、中断生成、引用来源展示。
- PostgreSQL 向量检索与多轮上下文；未检索到依据时明确说明资料不足。
- 主动导入本地 Markdown Wiki，切片后向量化；应用启动不会自动导入资料。
- 本地账号登录、会话归属校验、数据库消息与引用快照；页面可切换、重命名、删除会话并分页查看历史。
- 通用、本地、生产三份配置，私有本地配置与公开代码隔离。
- Flyway 初始化 SQL、数据库说明、Docker Compose 部署模板。

M0 已完成，M1 的数据库、身份和聊天持久化已实现并通过本机验证；文件存储与后台任务基础仍待开发。知识库管理页面、上传 ZIP、人工编辑切片、完整文档版本/增量同步、图片理解和 Thinking 开关尚未实现。预留表结构不代表这些功能已经实现。

详细需求、阶段任务、验收标准和每次进度记录见 [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)。数据库名称、表结构、SQL 使用和备份说明见 [docs/DATABASE_SETUP.md](docs/DATABASE_SETUP.md)。

## 本地启动

1. 准备 Java 21、Maven 或 Maven Wrapper、支持 pgvector 的 PostgreSQL、Node.js 22.12+。
2. 按数据库说明创建独立数据库 `lishou_agent`，不要连接原项目的 `brownie_ai_agent` 数据库。首次后端启动由 Flyway 创建表和扩展。
3. 配置 `config/application-local.yml`。此次本机迁移已经复制原项目的 DashScope Key 和数据库用户名/密码到该文件；从 GitHub 克隆时需自行复制 `.example` 并填写，或者提供环境变量。
4. 从项目根目录运行：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

5. 启动前端：

   ```powershell
   cd lishou-agent-frontend
   npm ci
   npm run dev
   ```

后端监听 `8124`；Vite 将 `/api` 代理至后端。健康接口：`GET /api/health`。前端地址以 Vite 启动日志为准。

首次使用需初始化登录账号。账号建立、添加同事和会话接口见 [docs/AUTH_AND_CHAT.md](docs/AUTH_AND_CHAT.md)。旧的 `.kryo` 文件历史不会自动进入数据库。

## Wiki 首次导入

本机迁移已将原 Wiki 与图片复制到 `data/wiki/source/`。从 GitHub 克隆不会带有这些私有文件，需要通过内部渠道复制到同一路径。目录层级和 Markdown 图片引用必须一起保留。

启动成功并登录后，可主动调用 `POST /api/knowledge/import/local`；此写接口需要登录 Cookie 和 CSRF Token。浏览器聊天页不会在启动时触发导入。

该操作调用配置的 Embedding 服务，会产生模型费用并把文本发送给该服务；构建和启动不会自动执行。它是基线导入接口，按来源文件替换向量，失败时通过数据库事务保留旧记录；空白 Markdown 会拒绝本次导入，避免静默保留旧内容。尚未提供完整任务管理、删除同步和人工修改保护。文档删除/大幅重写的增量处理将在 M2/M3 实现。

当前读取原始 UTF-8 Markdown，先按二级标题分章节，再在章节内用 TokenTextSplitter 限长切片；文档开头紧接“通用约定”时，两者作为一个章节切片。每段保留文件、章节和可识别的接口路径。提问明确包含接口 URL，或追问“这个接口”且历史问题含 URL 时，检索会限定到对应接口。更新此切片逻辑后，必须重启后端并手动再次调用上述导入接口，旧向量记录才会被替换并获得新元数据。多轮检索仍会拼接最近两次问题作为上下文；独立问题改写、复杂指代和真实业务效果仍需验证。

图片文件已保留，但当前检索只读取 Markdown 文本；图片里的业务信息需要后续补充文字说明或 OCR/视觉解析。引用展示目前展示来源文字，原文/图片预览尚未接入。

## 配置规则

| 文件 | 用途 | 是否提交 |
| --- | --- | --- |
| `src/main/resources/application.yml` | 通用模型名、端口、RAG 参数和路径 | 是；不含秘密 |
| `src/main/resources/application-local.yml` | 本地环境变量映射 | 是；不含秘密 |
| `src/main/resources/application-prod.yml` | 生产环境变量映射 | 是；不含秘密 |
| `config/application-local.yml` | 本机真实 Key 与数据库凭据 | 否；已被 Git 忽略 |
| `.env` | Docker Compose 的本地部署变量 | 否；已被 Git 忽略 |

`SPRING_PROFILES_ACTIVE` 默认是 `local`，生产必须设置为 `prod`。Spring Boot 会从项目根目录的 `config/` 自动读取外部本地配置；从其他工作目录启动时需通过 `SPRING_CONFIG_ADDITIONAL_LOCATION` 指定该路径。

环境变量：`DASHSCOPE_API_KEY`、`DB_USERNAME`、`DB_PASSWORD`、`DB_HOST`、`DB_PORT`、`DB_NAME`；可用 `DB_URL` 覆盖完整 JDBC URL。`CHAT_MODEL` 默认 `qwen-plus`，`EMBEDDING_MODEL` 默认 `text-embedding-v3`。向量固定 1024 维，切换 Embedding 模型需确认维度与兼容性，并重新索引。

如果私有 `config/application-local.yml` 直接写了固定的 JDBC URL，该外部配置会覆盖通用环境变量映射；需要改动私有配置，或用 `--spring.datasource.url=...` 启动参数临时指定数据库。不要在命令行填写密码。

## Docker Compose

```powershell
Copy-Item .env.example .env
# 在 .env 填入自己的数据库凭据和 DASHSCOPE_API_KEY
docker compose up -d --build
```

默认页面地址 `http://127.0.0.1:8080`，数据库宿主机端口 `5433`，避免默认与本机已有 PostgreSQL 冲突。后端在容器内部通过 `db:5432` 连接数据库。首次启动会自动创建 `lishou_agent` 并执行 Flyway。

`pg_data` 保存数据库中的会话与向量，Wiki 通过只读目录挂载。首次使用在私有 `.env` 中临时设置 `LISHOU_BOOTSTRAP_USERNAME` 和 `LISHOU_BOOTSTRAP_PASSWORD`；账号创建后清空这两个值。镜像不会打包真实 Key 或公司资料。修改 `.env` 中数据库用户名/密码不会重置已有数据卷的凭据。

Compose 默认只开放本机页面。面向同事部署前仍需验证部署环境的会话和账号流程。不要用 `docker compose down -v` 作为日常重启操作，它会删除数据库与会话数据卷。

## 开发验证

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
cd lishou-agent-frontend
npm ci
npm run build
```

默认单元测试不连接真实模型、不导入私有 Wiki、不操作真实数据库。设置 `LISHOU_DB_INTEGRATION=true` 可显式运行事务回滚的本地数据库测试。Docker 启动和真实问答验收仍需单独验证，结果写入开发计划。

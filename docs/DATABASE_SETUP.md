# lishouAgent 数据库初始化、结构与运维说明

更新时间：2026-10-04（Asia/Shanghai）。

本文记录当前迁移基线的数据库设计与初始化方法。**本次生成 SQL 和操作说明，没有连接原项目数据库，没有执行建库、建表、导入、删除、备份或恢复操作。当前环境没有可用的 Docker / psql，真实数据库验证待完成。**

## 1. 数据库与功能实现边界

| 项目 | 当前约定 |
| --- | --- |
| 数据库名称 | `lishou_agent`；与源项目数据库分开 |
| 数据库类型 | PostgreSQL 17 |
| 向量扩展 | `pgvector`，扩展名称 `vector` |
| Docker 镜像 | `pgvector/pgvector:pg17`，固定 PostgreSQL 主版本；正式部署可进一步固定镜像摘要 |
| schema | `public` |
| 向量维度 | `1024`，与当前 Embedding 请求配置保持一致 |
| 向量索引 | HNSW＋余弦距离（`vector_cosine_ops`） |
| 建表与结构升级 | Flyway；迁移目录 `src/main/resources/db/migration` |
| 数据库用户及密码 | 由本机私有配置或部署环境提供，无默认密码，不写入 Git |

PostgreSQL 同时存储普通业务表与向量表，不需要再运行一个独立向量服务。原始 Wiki Markdown 和图片存放在文件目录，数据库保存切片、路径与关联信息。

**当前迁移基线使用 `vector_store` 进行 Wiki 检索；会话仍使用迁移后的本地文件记忆。** 其他表是后续数据库会话、登录、文档版本、页面编辑与后台任务的基础模型；只有表结构，尚未实现对应接口和业务流程。表内没有预置用户或真实内部知识数据。

迁移的 Wiki 原文与图片位于 `data/wiki/source/`，属于私有数据，不提交到公开代码仓库。迁移这些文件不等于已经自动向量化入库；应用启动不重新导入全部文档，按 README 的导入入口进行处理。

## 2. 初始化 SQL 的唯一来源

| 文件 | 作用 | 执行方 |
| --- | --- | --- |
| [`database/00-create-database.sql`](../database/00-create-database.sql) | 创建 `lishou_agent`，安装 `vector`；数据库角色必须已经存在 | DBA／管理员，通过 psql 执行 |
| [`src/main/resources/db/migration/V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql) | 创建所有业务表、向量表、约束、索引和注释 | Flyway，在应用第一次启动时执行 |
| `public.flyway_schema_history` | 记录版本、校验和与迁移执行结果 | Flyway 自动维护 |

Flyway 能创建表，但不会创建需要连接的数据库。`CREATE DATABASE` 不能放进普通 Flyway 事务，因而与 V1 分开。V1 不创建数据库角色、不设置密码、不写测试账号。

不要另行维护一份重复的 `tables.sql`，也不要把 V1 重复挂载到 Docker 的 `/docker-entrypoint-initdb.d/`。表结构统一交给 Flyway。**已执行过的 V1 不再修改；后续调整追加 V2、V3。**

## 3. 配置文件与私有信息

配置按通用、本地、生产三种环境组织：

| 配置位置 | 用途 |
| --- | --- |
| `src/main/resources/application.yml` | 通用模型名称、1024 维配置、Flyway、知识目录、基础服务配置 |
| `src/main/resources/application-local.yml` | 本地环境配置入口；真实信息从环境变量或仓库外部的私有覆盖文件读取 |
| `config/application-local.yml` | 可复用原项目本地密钥的私有覆盖文件，必须被 Git 忽略 |
| `src/main/resources/application-prod.yml` | 生产配置，数据库和模型密钥全部读取环境变量 |
| `.env` | Docker Compose 环境配置，必须被 Git 忽略；从 `.env.example` 复制后本机填写 |

关键变量：

| 变量 | 含义 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 本地使用 `local`，容器使用 `prod` |
| `DB_NAME` | 默认 `lishou_agent` |
| `DB_HOST` / `DB_PORT` | 后端容器使用 `db:5432`；本机 Java 连接 Compose 数据库使用 `localhost:5433`（默认映射） |
| `DB_URL` | 可完整覆盖 JDBC 地址，如 `jdbc:postgresql://localhost:5433/lishou_agent` |
| `DB_USERNAME` / `DB_PASSWORD` | 当前数据库凭据，必须提供 |
| `DB_PUBLISHED_PORT` | Compose 数据库映射到本机的端口，默认 5433，仅绑定 127.0.0.1 |
| `DASHSCOPE_API_KEY` | 当前复用的模型服务密钥，真实值不进入本文或仓库 |
| `EMBEDDING_MODEL` | 默认 `text-embedding-v3`；更换模型需要重建知识索引 |
| `WIKI_SOURCE_DIR` | Wiki 原文与图片目录 |

复用模型 API Key 不意味着复用原项目数据库。请确保 JDBC 地址使用新的 `lishou_agent`。Compose 的 `.env` 由 Docker 读取，Java 本机启动不会自动加载它；本机启动时还需要正确的私有 local 配置或系统环境变量。

## 4. 推荐方法：Compose 启动数据库，Flyway 建表

在目标工程根目录执行，先将 `.env.example` 复制为 `.env`，在本机填写 `DB_USERNAME`、`DB_PASSWORD`、`DASHSCOPE_API_KEY` 等值：

```powershell
Copy-Item -LiteralPath '.env.example' -Destination '.env'
docker compose up -d db
docker compose ps
```

数据库镜像首次初始化空数据卷时，会按 `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` 建立数据库和账号。镜像带 pgvector 扩展文件；V1 的 `CREATE EXTENSION IF NOT EXISTS vector` 将扩展启用到当前数据库。

随后选择其中一种方式：

```powershell
# 同时启动容器内后端与前端，后端 prod 自动连接 db:5432
docker compose up -d --build
docker compose logs backend
```

或在本机开发：

```powershell
# 凭据在私有 config/application-local.yml 或本机环境中配置
$env:DB_URL = 'jdbc:postgresql://localhost:5433/lishou_agent'
.\mvnw.cmd spring-boot:run
```

应用启动时 Flyway 先执行 V1；`PgVectorStore` 关闭自身建表（`initializeSchema(false)`），由 Flyway 管理表结构，避免两套建表逻辑冲突。构建成功不等于模型调用和数据库链路已验证，请记录实际启动日志及后续检索结果。

如果数据库数据卷已经存在，修改 `.env` 里的 `POSTGRES_*` 对应变量不会自动重建账号、改密码或重命名数据库。应通过 DBA 操作处理变更，不通过删除数据卷解决。

## 5. 已有 PostgreSQL：管理员手工创建数据库

确认 PostgreSQL 服务端已经安装 pgvector。普通 PostgreSQL 镜像未必包含此扩展文件，单纯执行 `CREATE EXTENSION` 不能代替安装扩展。

管理员使用已有角色，或者通过交互方式创建一个应用角色，密码不写进 SQL 文件：

```text
psql -h localhost -p 5432 -U postgres -d postgres
```

在交互式 psql 中执行（仅当该角色不存在）：

```sql
CREATE ROLE lishou_agent LOGIN;
\password lishou_agent
\q
```

然后在项目根目录执行初始化脚本：

```text
psql -h localhost -p 5432 -U postgres -d postgres -v db_owner=lishou_agent -f database/00-create-database.sql
```

脚本要求 `db_owner` 是已存在角色；`db_owner` 名称通过 psql 标识符格式化引用。脚本在数据库不存在时创建 UTF-8 空库，数据库已存在时保留其所有者及数据。随后在新库安装 `vector`，不执行业务表 DDL。

建库需要管理员／`CREATEDB` 权限，安装 pgvector 通常需要管理员权限。首次 V1 需要对 `public` schema 的 `USAGE`、`CREATE` 权限和建表权限。如果 pgvector 已由管理员装在当前库，V1 不重复创建。若 `public` schema 权限被收紧，可由管理员明确授权给数据库所有者：

```sql
-- 在 lishou_agent 内执行；角色名与实际配置一致。
GRANT USAGE, CREATE ON SCHEMA public TO lishou_agent;
```

填写本机私有配置，启动应用让 Flyway 建表。部署后如需将迁移账号和运行账号分离，应新增经过验证的权限配置：迁移账号负责 DDL，运行账号只需要相关表的读写权限；当前 Compose 的初始化账号由镜像创建，可能具有超级用户权限。

## 6. 首次 Flyway 与已有手工表的处理

默认 `baseline-on-migrate=false`、`validate-on-migrate=true`，防止不经核对就接管已有表。

- **新建空库**：按推荐步骤启动应用，V1 自动执行，无需 baseline。
- **原 brownie-ai-agent 数据库已经有 vector_store**：不要把新项目直接连接到原库，也不要开启 baseline 跳过检查；建立独立 `lishou_agent` 后重新导入公司资料。
- **新库只有部分手工表**：先备份并核对结构，制定专门的接管迁移；不能将其标记成已经完成 V1。
- **DBA 已完整手工执行当前 V1**：核对全部 11 张表、字段、索引和外键与本文件一致后，才能为该库设置一次性的 Flyway baseline 版本 1。记录具体操作，然后恢复默认禁止自动 baseline；之后由 V2 继续管理。

不应在不完整的数据库上设置 baseline 版本 1，这会跳过本来应该建表的 V1；也不应在已有 V1 表上 baseline 版本 0 后直接重放 V1。不要修改 `flyway_schema_history` 的校验和来掩盖脚本变化。迁移失败时先查日志和数据库状态，确认失败原因后按 Flyway 的实际状态处理。

## 7. 表结构与关系

以下表全部采用 UUID 主键；`timestamptz` 保存时间；无业务内容和账号的种子数据。

| 表 | 主要数据和约束 | 当前用途 |
| --- | --- | --- |
| `app_user` | 用户名（忽略大小写唯一）、密码摘要／外部身份、状态 | 后续身份入口；不会自动产生默认用户 |
| `chat_session` | 所属用户、标题、状态、时间 | 后续个人会话列表与归属校验 |
| `chat_message` | 会话、唯一顺序号、角色、完整内容、生成状态及 Token 信息 | 后续完整消息存档，不随上下文窗口删除 |
| `chat_message_source` | 消息对应的引用序号、文档／版本／切片关联及正文快照 | 后续历史回答引用；删除原文后快照仍在 |
| `knowledge_document` | 稳定 source_key、项目、标题、来源、生效版本指针 | 后续稳定文档身份与分类 |
| `knowledge_document_version` | 版本号、SHA-256、原文件路径、解析内容及发布状态 | 后续去重与更新，保留来源版本 |
| `knowledge_asset` | 文档版本、图片相对路径、实际路径、文字说明及确认状态 | 后续图片关联与可检索说明 |
| `knowledge_chunk` | 稳定切片键、章节、顺序、生效修订、状态、lock_version | 后续切片维护与并发编辑保护 |
| `knowledge_chunk_revision` | 原始正文、当前正文、人工编辑标记、模型、向量记录及发布状态 | 后续切片编辑与重新向量化 |
| `vector_store` | `id uuid`、`content text`、`metadata jsonb`、`embedding vector(1024)` | 当前 Spring AI 文档检索 |
| `ingest_task` | 导入／重索引类型、阶段、进度、状态、失败原因与尝试次数 | 后续可重试任务和重启中断处理 |

主要关系：用户 → 会话 → 消息 → 引用；文档 → 版本 → 图片；文档 → 稳定切片 → 修订 → 向量。生效文档版本和切片修订的复合外键确保指针指向其自身的版本，允许在同一事务内切换生效指针。引用关联在硬删除来源时置空，标题、章节和正文快照保留。

### 7.1 需要业务代码配合的规则

数据库约束不能代替完整业务实现，后续服务必须完成：

1. **发布切片**：先在数据库外完成 Embedding 请求，再事务性保存相匹配的正文、向量、版本状态及生效指针。向量表只保留生效可检索数据；草稿／失效向量不应进入正常检索范围。
2. **保护旧版**：索引失败时旧生效记录不变；版本切换时先把旧 `ACTIVE` 标记为 `SUPERSEDED`，再激活新版，满足每个文档／切片最多一个 ACTIVE 的唯一索引。
3. **并发编辑**：更新时检查 `lock_version`，成功后递增；表里有该字段不意味着接口已经实现乐观锁。
4. **更新时间**：`created_at` / `updated_at` 默认值只在插入时生效，更新语句必须主动设置 `updated_at`。本版没有自动更新时间触发器。
5. **完整聊天**：数据库归档保留全部历史，模型上下文读取最近消息／摘要；后端校验会话所属用户，并通过事务／锁分配顺序号。
6. **去重**：content_sha256 表示原文或切片内容哈希；应用比较稳定身份和哈希决定重用、更新或冲突处理，不能仅靠表结构自动去重。
7. **删除与停用**：清理／移除相关向量后再停用内容；删除生效版本或修订前，先在事务内清空或替换生效指针。修改状态字段本身不会自动清理向量。删除 document／chunk／revision 也不会反向级联删除 vector_store：后续业务必须按绑定 ID／metadata 显式清理向量，并与发布／删除操作保持事务一致，否则残留片段仍可被检索。当前迁移基线没有文档／切片删除接口。
8. **文件保护**：storage_path、relative_path 保存经过应用校验的路径；数据库文本字段不会自动阻止 ZIP 越界或错误图片路径。

向量记录可通过 metadata 记录 `documentId`、`documentVersionId`、`chunkId`、`revisionId`、`sourcePath`、标题、章节、项目与来源信息。当前迁移基线和未来管理模块应统一 metadata 字段命名；`documentId` / `sourcePath` 已有表达式索引。未来草稿发布机制必须确保正常查询只命中已生效内容。

## 8. 初始化后的检查

以下是待执行的检查 SQL，请连接到 `lishou_agent`，不要连接原项目库：

```sql
SELECT current_database(), current_user;
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

SELECT installed_rank, version, description, success
FROM public.flyway_schema_history
ORDER BY installed_rank;

SELECT column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'vector_store'
ORDER BY ordinal_position;

SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS column_type
FROM pg_attribute a
WHERE a.attrelid = 'public.vector_store'::regclass AND a.attname = 'embedding';

SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'vector_store';
SELECT count(*) AS indexed_chunks FROM public.vector_store;
```

预期有 11 张业务／向量表和 Flyway 历史表，V1 状态为 success，embedding 类型为 vector(1024)，HNSW 索引使用 cosine 操作符。首次初始化向量表为空是正常现象，需要显式导入 Wiki 才会有数据。

## 9. 持久化与模型变更

Compose 的 `pg_data` 卷挂载 PostgreSQL 17 的 `/var/lib/postgresql/data`，`chat_memory` 卷保存当前过渡阶段的文件记忆；Wiki 原文与图片通过 `./data/wiki/source` 挂载到后端。普通容器重建继续使用同一卷可保留数据，`docker compose down -v` 会删除命名卷，不应作为常规重启命令。

当前 Wiki 挂载为只读，适用于迁移基线读取和导入。后续上传页面需要新增可写的上传目录和独立存储配置，不能把只读挂载当作已经完成文件管理。

Embedding 维度为 1024，模型或维度改变时需要完整重新向量化。即使维度相同，不同模型的向量空间也不能混用。应新增 Flyway 迁移／新索引表，构建和验证新索引，再切换检索配置；旧索引先保留回退能力。仅修改配置中的数字不会自动修改已有 vector(1024) 列，也不会重算旧向量。

数据库主版本升级不能直接让另一主版本镜像打开原数据目录；另行使用备份恢复或升级工具并验证 pgvector 兼容性。

## 10. 备份与恢复演示（本次未执行）

数据库备份不包含原始 Wiki 文件和图片，必须分别备份 `data/wiki/source`、后续上传目录，以及当前过渡阶段的文件会话目录／卷。建议在停止导入和写操作的窗口制作成组备份，记录时间、Git 版本、镜像版本和 Embedding 模型，保证文档、向量及文件关联一致。

### 10.1 Docker 数据库备份

以下命令中的 `YOUR_DB_USERNAME` 是占位符，替换为 `.env` 的实际数据库账号；密码不写进命令：

```powershell
New-Item -ItemType Directory -Force -Path '.\backups'
docker compose exec -T db pg_dump -U YOUR_DB_USERNAME -d lishou_agent -Fc -f /tmp/lishou_agent.dump
docker compose cp db:/tmp/lishou_agent.dump .\backups\lishou_agent.dump
Compress-Archive -LiteralPath '.\data\wiki\source' -DestinationPath '.\backups\wiki-source.zip'
```

通过容器内 `-f` 生成二进制 dump，再复制到主机，避免某些 Windows PowerShell 版本的输出重定向损坏二进制数据。正式备份使用带日期的文件名，避免无意覆盖旧备份。备份含内部资料，应与原文一样受控保存，`backups/` 不提交 Git。

### 10.2 在独立新库恢复验证

演示恢复目标 `lishou_agent_restore_check` 必须是独立空库；不要将恢复命令直接指向在线业务库。连接角色需要建库与扩展权限：

```powershell
docker compose exec -T db createdb -U YOUR_DB_USERNAME lishou_agent_restore_check
docker compose cp .\backups\lishou_agent.dump db:/tmp/lishou_agent_restore.dump
docker compose exec -T db pg_restore -U YOUR_DB_USERNAME -d lishou_agent_restore_check --no-owner --no-privileges --exit-on-error /tmp/lishou_agent_restore.dump
```

恢复环境也需安装相同版本的 pgvector，原文与图片恢复到另一个目录，单独配置测试后端读取该目录和检查库；不要和生产服务同时写同一份文件。核对 Flyway 版本、行数、来源图片、样例检索、会话数据和切片修订，之后再考虑正式恢复流程。

## 11. 当前验证记录

| 项目 | 结果 |
| --- | --- |
| SQL 文件及文档生成 | 已完成 |
| 凭据／种子账号 | 未写入真实凭据，也没有默认账号 |
| 结构设计 | 已静态检查 UUID、外键、状态、唯一索引及 vector(1024) 接口 |
| Spring AI 兼容性核对 | 对照本机 `spring-ai-pgvector-store-1.1.2.jar` 中 PgVectorStore 字段／插入接口，保留 id/content/metadata/embedding 四列接口 |
| 实际建库／Flyway／向量写入 | 待验证；本次没有执行 |
| 容器重建／持久化／备份恢复 | 待验证；本次没有执行 |
| 历史会话、身份及知识管理流程 | 后续开发任务，不因建表完成而标记实现 |

后续实际验证结果同时更新 [`DEVELOPMENT_PLAN.md`](../DEVELOPMENT_PLAN.md) 的对应任务、验收状态和日志。

# 数据库初始化入口

- 数据库：`lishou_agent`；schema：`public`；PostgreSQL 17＋pgvector。
- `00-create-database.sql`：供管理员使用 `psql` 创建数据库及安装扩展，不创建账号或密码，不修改已有数据库的数据。
- 表结构只有一份正式来源：[`V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql)，由应用启动时的 Flyway 管理。后续结构调整新增 `V2__...sql`，不要改已经执行过的 V1。
- 操作步骤、配置、表关系、持久化及备份恢复见 [`docs/DATABASE_SETUP.md`](../docs/DATABASE_SETUP.md)。

当前仅生成并静态审查初始化 SQL，尚未对真实 PostgreSQL 执行验证；建模不代表历史会话、登录及知识管理功能已经实现。

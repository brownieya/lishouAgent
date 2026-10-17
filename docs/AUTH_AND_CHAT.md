# 本地账号与会话

本阶段使用 PostgreSQL 的 `app_user`、`chat_session`、`chat_message` 和 `chat_message_source`。登录后浏览器保留会话 Cookie，写请求使用 CSRF Token。所有会话查询和发送都按登录用户校验归属。

## 初始化第一个账号

后端每次启动都会检查 `app_user`。仅当表完全为空时，自动创建账号 `admin`，初始密码为 `123456`，数据库中只保存 BCrypt 摘要；已有账号不会被覆盖，也不会因重启重置密码。首次登录后在聊天页侧栏点击“修改密码”，设置新的非空密码，再把服务开放给其他人。此默认密码公开且固定，不适合长期使用。

## 添加同事账号

已登录用户可调用 `POST /api/auth/users`，提交 `username`、`displayName`、`password`；密码不能为空。`GET /api/auth/users` 列出账号，`GET /api/auth/users/{id}` 查询单个账号。`PATCH /api/auth/users/{id}` 只允许本人修改 `displayName` 和/或 `password`；`DELETE /api/auth/users/{id}` 只允许本人停用账号，并立即退出登录，已有聊天数据保留。最后一个可登录账号不能停用。用户名不可修改，停用账号不能重新登录。

第一版用户具有相同的知识维护权限，没有复杂角色管理。页面提供登录、退出、修改本人密码和会话管理；其他账号管理暂通过 API 完成。写请求需要登录 Cookie 和 CSRF Token。

PowerShell 调用需要复用同一个 Web 会话，并给写请求带上 `X-XSRF-TOKEN`。下面示例登录后调用本地 Wiki 导入；导入会向配置的 Embedding 服务发送 Wiki 文本，应由用户主动执行。

```powershell
$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$csrf = (Invoke-RestMethod 'http://localhost:8124/api/auth/csrf' -WebSession $webSession).token
$username = Read-Host '账号名'
$secret = Read-Host '密码' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $body = @{ username = $username; password = $password } | ConvertTo-Json
    Invoke-RestMethod 'http://localhost:8124/api/auth/login' -Method Post -ContentType 'application/json' -Headers @{ 'X-XSRF-TOKEN' = $csrf } -WebSession $webSession -Body $body
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Variable password, body -ErrorAction SilentlyContinue
}
Invoke-RestMethod 'http://localhost:8124/api/knowledge/import/local' -Method Post -Headers @{ 'X-XSRF-TOKEN' = $csrf } -WebSession $webSession
```

## 会话接口

- `GET /api/chat/sessions`：自己的会话列表。
- `POST /api/chat/sessions`：新建会话。
- `PATCH /api/chat/sessions/{id}`：修改标题，JSON 请求体为 `{"title":"..."}`。
- `DELETE /api/chat/sessions/{id}`：软删除会话。
- `GET /api/chat/sessions/{id}/messages?limit=50&before=...`：按序号向前分页，默认 50 条，最多 100 条。
- `POST /api/ai/chat/stream`：JSON 请求体为 `{"chatId":"会话 UUID","message":"问题"}`。会话必须属于登录用户。

消息在发送时写入数据库；生成完成后存答案和引用快照。失败或中断的回答保留对应状态。模型只读取最近 20 条已完成消息作为上下文，不删除完整历史。

旧的 `data/chat-memory/*.kryo` 不会自动迁移到新会话表；如需保留，请先另行备份。现有本地问答资料和向量数据不受账号初始化影响。

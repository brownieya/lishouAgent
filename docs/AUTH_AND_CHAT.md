# 本地账号与会话

本阶段使用 PostgreSQL 的 `app_user`、`chat_session`、`chat_message` 和 `chat_message_source`。登录后浏览器保留会话 Cookie，写请求使用 CSRF Token。所有会话查询和发送都按登录用户校验归属。

## 初始化第一个账号

首次启动且 `app_user` 为空时，在启动后端的 PowerShell 会话中显式设置两个环境变量：

```powershell
$env:LISHOU_BOOTSTRAP_USERNAME = Read-Host '账号名'
$secret = Read-Host '至少 12 位密码' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $env:LISHOU_BOOTSTRAP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
mvn spring-boot:run
```

启动后账号会写入数据库，密码保存为 BCrypt 摘要。后续启动不再需要这两个变量。停止应用后，在同一 PowerShell 会话执行：

```powershell
Remove-Item Env:LISHOU_BOOTSTRAP_USERNAME,Env:LISHOU_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
```

账号密码不要写入 Git 跟踪的配置、脚本或命令历史。已有账号不会因再次设置初始化变量而覆盖。

## 添加同事账号

已登录用户可调用 `POST /api/auth/users`，提交 `username`、`displayName`、`password`；密码至少 12 位。第一版用户具有相同的知识维护权限，没有复杂角色管理。页面目前提供登录、退出和会话管理；新增账号暂通过 API 完成。

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

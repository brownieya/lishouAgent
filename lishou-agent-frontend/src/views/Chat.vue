<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import ChatWindow from '@/components/ChatWindow.vue'
import { currentUser, login, logout } from '@/api/auth'
import { connectChat, createSession, deleteSession, listMessages, listSessions, renameSession } from '@/api/chat'
import { generateId } from '@/utils/uuid'

const user = ref(null)
const username = ref('')
const password = ref('')
const authError = ref('')
const busy = ref(false)
const sessions = ref([])
const chatId = ref(null)
const messages = ref([])
const hasMore = ref(false)
const pending = ref(false)
const connectionState = ref('idle')
let activeConnection = null
let activeMessage = null

const statusText = computed(() => ({
  connecting: '连接中…', streaming: '正在回答…', error: '回答中断', idle: '就绪',
}[connectionState.value]))

onMounted(async () => {
  try {
    user.value = await currentUser()
    await refreshSessions()
    if (sessions.value.length) await openSession(sessions.value[0].id)
  } catch { /* 未登录时显示登录表单。 */ }
})
onBeforeUnmount(() => activeConnection?.close())

async function handleLogin() {
  authError.value = ''
  busy.value = true
  try {
    user.value = await login(username.value.trim(), password.value)
    password.value = ''
    await refreshSessions()
    if (sessions.value.length) await openSession(sessions.value[0].id)
  } catch { authError.value = '登录失败，请检查账号和密码。' }
  finally { busy.value = false }
}

async function handleLogout() {
  activeConnection?.close()
  await logout()
  user.value = null
  chatId.value = null
  sessions.value = []
  messages.value = []
  hasMore.value = false
}

async function refreshSessions() { sessions.value = await listSessions() }

// 切换会话时从数据库读取自己的历史消息，失败/中断的答案保留状态。
async function openSession(id) {
  if (pending.value) return
  const rows = await listMessages(id)
  chatId.value = id
  messages.value = rows.map(toMessage)
  hasMore.value = rows.length === 50
  connectionState.value = 'idle'
}

function toMessage(row) {
  return {
    id: row.id, role: row.role === 'USER' ? 'user' : 'ai', content: row.content,
    sequenceNo: row.sequenceNo,
    status: row.status === 'COMPLETE' ? 'done' : 'error', sources: row.sources || [],
    errorMessage: row.status === 'COMPLETE' ? '' : `本条回答${row.status === 'INTERRUPTED' ? '已中断' : '未完成'}。`,
  }
}

// 点击“加载更早消息”时用最早序号向前翻页，不裁剪已加载的历史。
async function loadEarlier() {
  if (!chatId.value || !messages.value.length) return
  const rows = await listMessages(chatId.value, messages.value[0].sequenceNo)
  messages.value = [...rows.map(toMessage), ...messages.value]
  hasMore.value = rows.length === 50
}

async function newChat() {
  if (pending.value) return
  const session = await createSession()
  await refreshSessions()
  await openSession(session.id)
}

async function handleRename(session) {
  const title = window.prompt('会话名称', session.title)?.trim()
  if (!title || title === session.title) return
  await renameSession(session.id, title)
  await refreshSessions()
}

async function handleDelete(session) {
  if (!window.confirm(`删除会话“${session.title}”？`)) return
  await deleteSession(session.id)
  await refreshSessions()
  if (chatId.value === session.id) {
    chatId.value = null
    messages.value = []
    hasMore.value = false
    if (sessions.value.length) await openSession(sessions.value[0].id)
  }
}

function stopGeneration() {
  activeConnection?.close()
  if (activeMessage) {
    activeMessage.status = 'stopped'
    activeMessage.errorMessage = '生成已停止，回答可能不完整。'
  }
  pending.value = false
  connectionState.value = 'idle'
}

// 发送时先确保后端会话存在，再按 SSE 事件更新当前答案；服务端负责归档。
async function handleSend(text) {
  if (pending.value) return
  try { if (!chatId.value) await newChat() }
  catch { connectionState.value = 'error'; return }
  messages.value.push({ id: generateId(), role: 'user', content: text, status: 'done' })
  const message = reactive({
    id: generateId(), role: 'ai', content: '', status: 'streaming', sources: [], errorMessage: '',
  })
  messages.value.push(message)
  activeMessage = message
  pending.value = true
  connectionState.value = 'connecting'
  activeConnection = connectChat(text, chatId.value, {
    onOpen: () => { connectionState.value = 'streaming' },
    onAnswer: (chunk) => { message.content += chunk },
    onSources: (sources) => { message.sources = sources },
    onDone: () => {
      message.status = message.content ? 'done' : 'error'
      message.errorMessage = message.content ? '' : '本次未收到回答，请重试。'
      pending.value = false
      connectionState.value = message.content ? 'idle' : 'error'
      refreshSessions()
    },
    onError: (error) => {
      message.status = 'error'
      message.errorMessage = error.message || '连接异常，请稍后重试。'
      pending.value = false
      connectionState.value = 'error'
    },
  })
}
</script>

<template>
  <div class="theme-lishou page">
    <form v-if="!user" class="login" @submit.prevent="handleLogin">
      <h1>lishouAgent</h1><p>公司 Wiki 知识助手</p>
      <label>账号<input v-model="username" autocomplete="username" required /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
      <p v-if="authError" role="alert">{{ authError }}</p>
      <button :disabled="busy" type="submit">登录</button>
    </form>
    <div v-else class="workspace">
      <aside class="sidebar">
        <div class="sidebar__top"><strong>会话历史</strong><button @click="newChat">＋ 新建</button></div>
        <div class="sidebar__list">
          <div v-for="session in sessions" :key="session.id" class="session" :class="{ active: chatId === session.id }">
            <button class="session__title" @click="openSession(session.id)">{{ session.title }}</button>
            <button title="重命名" aria-label="重命名" @click="handleRename(session)">✎</button>
            <button title="删除" aria-label="删除" @click="handleDelete(session)">×</button>
          </div>
        </div>
        <div class="sidebar__user"><span>{{ user.username }}</span><button @click="handleLogout">退出</button></div>
      </aside>
      <ChatWindow
        title="lishouAgent" :subtitle="chatId ? `公司 Wiki 知识助手 · 会话 ${chatId.slice(0, 8)}` : '公司 Wiki 知识助手'"
        :messages="messages" :has-more="hasMore" :pending="pending" :status-text="statusText" :status-state="connectionState"
        placeholder="询问公司项目、业务流程或操作方法…" empty-title="从公司知识中找到答案"
        empty-hint="可以询问项目介绍、业务流程和操作步骤，并查看回答引用的资料。"
        @send="handleSend" @new-chat="newChat" @stop="stopGeneration" @load-more="loadEarlier"
      />
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--bg-gradient); color: var(--neutral-100); }
.workspace { display: grid; grid-template-columns: 250px minmax(0, 1fr); min-height: 100vh; }
.workspace :deep(.chat) { min-height: 100vh; }
.sidebar { display: flex; flex-direction: column; padding: 22px 12px; border-right: 1px solid rgba(255,255,255,.12); background: rgba(5,10,27,.55); }
.sidebar__top, .sidebar__user { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px; }
.sidebar button, .login button { color: inherit; border: 1px solid rgba(255,255,255,.12); border-radius: 8px; background: rgba(255,255,255,.07); padding: 6px 9px; cursor: pointer; }
.sidebar__list { flex: 1; overflow: auto; margin-top: 18px; }
.session { display: flex; align-items: center; margin-bottom: 4px; border-radius: 8px; }
.session.active { background: rgba(255,255,255,.09); }
.session__title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: left; border: 0 !important; background: transparent !important; }
.sidebar__user { border-top: 1px solid rgba(255,255,255,.12); }
.login { width: min(360px, calc(100vw - 36px)); margin: 0 auto; padding-top: 20vh; display: grid; gap: 18px; }
.login label { display: grid; gap: 7px; }
.login input { padding: 11px; border-radius: 8px; border: 1px solid rgba(255,255,255,.2); background: rgba(255,255,255,.08); color: #fff; }
.login button { padding: 11px; background: var(--accent-gradient); }
@media (max-width: 700px) { .workspace { grid-template-columns: 1fr; } .sidebar { max-height: 180px; } .sidebar__list { display: flex; gap: 4px; overflow-x: auto; margin-top: 4px; } .session { min-width: 160px; } }
</style>

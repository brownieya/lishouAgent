<script setup>
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import ChatWindow from '@/components/ChatWindow.vue'
import { connectChat } from '@/api/chat'
import { generateId } from '@/utils/uuid'

const chatId = ref(generateId())
const messages = ref([])
const pending = ref(false)
const connectionState = ref('idle')
let activeConnection = null
let activeMessage = null

const statusText = computed(() => ({
  connecting: '连接中…',
  streaming: '正在回答…',
  error: '回答中断',
  idle: '就绪',
}[connectionState.value]))

onBeforeUnmount(() => activeConnection?.close())

function newChat() {
  if (pending.value) return
  chatId.value = generateId()
  messages.value = []
  connectionState.value = 'idle'
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

function handleSend(text) {
  if (pending.value) return
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
  <div class="theme-lishou">
    <ChatWindow
      title="lishouAgent"
      :subtitle="`公司 Wiki 知识助手 · 会话 ${chatId.slice(0, 8)}`"
      :messages="messages"
      :pending="pending"
      :status-text="statusText"
      :status-state="connectionState"
      placeholder="询问公司项目、业务流程或操作方法…"
      empty-title="从公司知识中找到答案"
      empty-hint="可以询问项目介绍、业务流程和操作步骤，并查看回答引用的资料。"
      @send="handleSend"
      @new-chat="newChat"
      @stop="stopGeneration"
    />
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import AppIcon from './icons/AppIcon.vue'
import MessageBubble from './MessageBubble.vue'

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  aiIcon: { type: String, default: 'bot' },
  messages: { type: Array, required: true },
  pending: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息，按 Enter 发送…' },
  emptyTitle: { type: String, default: '开始一段新的对话' },
  emptyHint: { type: String, default: '' },
  statusText: { type: String, default: '就绪' },
  statusState: { type: String, default: 'idle' }, // idle | connecting | streaming | error
})

const emit = defineEmits(['send', 'new-chat', 'stop'])

const draft = ref('')
const scrollRef = ref(null)
const textareaRef = ref(null)

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(
  () => props.messages,
  () => scrollToBottom(),
  { deep: true },
)

function autoGrow() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

function handleSend() {
  const text = draft.value.trim()
  if (!text || props.pending) return
  emit('send', text)
  draft.value = ''
  nextTick(autoGrow)
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    handleSend()
  }
}

defineExpose({ scrollToBottom })
</script>

<template>
  <div class="chat">
    <div class="chat__bg" aria-hidden="true" />

    <div class="chat__panel">
      <header class="chat__header">
        <button class="chat__back" type="button" :disabled="pending" aria-label="新建会话" title="新建会话" @click="emit('new-chat')">
          <AppIcon name="plus" :size="18" />
        </button>

        <div class="chat__heading">
          <span class="chat__icon">
            <AppIcon :name="aiIcon" :size="18" />
          </span>
          <div>
            <h1 class="chat__title">{{ title }}</h1>
            <p v-if="subtitle" class="chat__subtitle">{{ subtitle }}</p>
          </div>
        </div>

        <span class="chat__status" :class="`chat__status--${statusState}`">
          <i class="chat__status-dot" />
          {{ statusText }}
        </span>
      </header>

      <div ref="scrollRef" class="chat__scroll">
        <div v-if="messages.length === 0" class="chat__empty">
          <span class="chat__empty-icon">
            <AppIcon :name="aiIcon" :size="26" />
          </span>
          <p class="chat__empty-title">{{ emptyTitle }}</p>
          <p v-if="emptyHint" class="chat__empty-hint">{{ emptyHint }}</p>
        </div>

        <div v-else class="chat__list">
          <MessageBubble
            v-for="message in messages"
            :key="message.id"
            :role="message.role"
            :content="message.content"
            :status="message.status"
            :sources="message.sources"
            :error-message="message.errorMessage"
            :ai-icon="aiIcon"
          />
        </div>
      </div>

      <form class="chat__input" @submit.prevent="handleSend">
        <textarea
          ref="textareaRef"
          v-model="draft"
          class="chat__textarea"
          rows="1"
          :placeholder="placeholder"
          :disabled="pending"
          @input="autoGrow"
          @keydown="handleKeydown"
        />
        <button v-if="pending" class="chat__send chat__stop" type="button" aria-label="停止生成" title="停止生成" @click="emit('stop')">
          <AppIcon name="stop" :size="17" />
        </button>
        <button
          v-else
          class="chat__send"
          type="submit"
          :disabled="pending || !draft.trim()"
          aria-label="发送"
        >
          <AppIcon name="send" :size="17" />
        </button>
      </form>
      <p class="chat__hint">Enter 发送 · Shift + Enter 换行 · 刷新页面将开启新会话</p>
    </div>
  </div>
</template>

<style scoped>
.chat {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-5);
}

.chat__bg {
  position: absolute;
  inset: 0;
  background: var(--bg-gradient);
  z-index: 0;
}

.chat__panel {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 760px;
  height: min(88vh, 860px);
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.07) 0%, rgba(255, 255, 255, 0.03) 100%);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.chat__header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.chat__back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.04);
  color: var(--neutral-100);
  transition: background var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
}

.chat__back:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateX(-2px);
}

.chat__back:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.chat__heading {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1;
  min-width: 0;
}

.chat__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--radius-sm);
  background: var(--accent-gradient);
  color: #fff;
  flex-shrink: 0;
}

.chat__title {
  font-size: 16px;
  font-weight: 700;
  color: var(--neutral-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat__subtitle {
  font-size: 12px;
  color: var(--neutral-400);
  margin-top: 2px;
}

.chat__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--neutral-400);
  padding: 5px 10px;
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
  white-space: nowrap;
}

.chat__status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--neutral-400);
}

.chat__status--connecting .chat__status-dot,
.chat__status--streaming .chat__status-dot {
  background: var(--accent-1);
  animation: pulse-dot 1.2s ease-in-out infinite;
}

.chat__status--error {
  color: #f87171;
  border-color: rgba(248, 113, 113, 0.3);
}
.chat__status--error .chat__status-dot {
  background: #f87171;
}

@keyframes pulse-dot {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.5;
    transform: scale(1.3);
  }
}

.chat__scroll {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
  scroll-behavior: smooth;
}

.chat__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.chat__empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: var(--space-2);
  color: var(--neutral-400);
}

.chat__empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-1);
  margin-bottom: var(--space-2);
}

.chat__empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--neutral-100);
}

.chat__empty-hint {
  font-size: 13px;
  max-width: 320px;
  line-height: 1.6;
}

.chat__input {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  margin: 0 var(--space-4) 2px;
  border-radius: var(--radius-lg);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
}

.chat__textarea {
  flex: 1;
  resize: none;
  max-height: 160px;
  border: none;
  outline: none;
  background: transparent;
  color: var(--neutral-0);
  font-size: 14.5px;
  line-height: 1.5;
  padding: 8px 4px;
}

.chat__textarea::placeholder {
  color: var(--neutral-400);
}

.chat__textarea:disabled {
  opacity: 0.6;
}

.chat__send {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: var(--accent-gradient);
  color: #fff;
  box-shadow: var(--shadow-sm);
  transition: transform var(--duration-fast) var(--ease-standard), opacity var(--duration-fast) var(--ease-standard);
}

.chat__send:hover:not(:disabled) {
  transform: scale(1.06);
}

.chat__send:active:not(:disabled) {
  transform: scale(0.96);
}

.chat__send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.chat__stop {
  background: var(--neutral-700);
  border: 1px solid var(--neutral-400);
}

.chat__hint {
  text-align: center;
  font-size: 11px;
  color: var(--neutral-600);
  padding: var(--space-2) 0 var(--space-4);
  flex-shrink: 0;
}

@media (max-width: 600px) {
  .chat { padding: 0; }
  .chat__panel { height: 100dvh; border-radius: 0; }
  .chat__header { padding: var(--space-4); }
  .chat__scroll { padding: var(--space-4); }
  .chat__subtitle { max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
</style>

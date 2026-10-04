import { createSSEConnection } from './sse'

const BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export function connectChat(message, chatId, handlers) {
  return createSSEConnection(`${BASE_URL}/ai/chat/stream`, { message, chatId }, handlers)
}

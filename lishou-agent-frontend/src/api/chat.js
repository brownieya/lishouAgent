import { createSSEConnection } from './sse'
import { apiRequest, csrfHeader } from './auth'

const BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export function connectChat(message, chatId, handlers) {
  return createSSEConnection(`${BASE_URL}/ai/chat/stream`, { message, chatId }, handlers, csrfHeader)
}

export const listSessions = () => apiRequest('/chat/sessions')
export const createSession = () => apiRequest('/chat/sessions', { method: 'POST' })
export const renameSession = (id, title) => apiRequest(`/chat/sessions/${id}`, {
  method: 'PATCH', body: JSON.stringify({ title }),
})
export const deleteSession = (id) => apiRequest(`/chat/sessions/${id}`, { method: 'DELETE' })
export const listMessages = (id, before = Number.MAX_SAFE_INTEGER) =>
  apiRequest(`/chat/sessions/${id}/messages?limit=50&before=${before}`)

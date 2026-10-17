const BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
let token = null

// 所有写请求先读取服务器的 CSRF Token；登录后重新读取新会话的 Token。
export async function csrfHeader() {
  if (!token) {
    const response = await fetch(`${BASE_URL}/auth/csrf`)
    if (!response.ok) throw new Error('无法读取登录校验信息。')
    token = (await response.json()).token
  }
  return { 'X-XSRF-TOKEN': token }
}

export async function apiRequest(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers }
  if (options.method && options.method !== 'GET') Object.assign(headers, await csrfHeader())
  const response = await fetch(`${BASE_URL}${path}`, { ...options, headers })
  if (response.status === 401) throw new Error('请先登录。')
  if (!response.ok) throw new Error(`操作失败（HTTP ${response.status}）。`)
  return response.status === 204 ? null : response.json()
}

export async function login(username, password) {
  const user = await apiRequest('/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  })
  token = null
  return user
}

export async function logout() {
  await apiRequest('/auth/logout', { method: 'POST' })
  token = null
}

export const currentUser = () => apiRequest('/auth/me')

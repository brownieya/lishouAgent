/** Parse SSE lines even when UTF-8 chunks, CRLF, and events cross network boundaries. */
export function createSSEParser(onEvent) {
  let line = ''
  let previousWasCR = false
  let event = 'message'
  let data = []

  function processLine() {
    if (line === '') {
      if (data.length) onEvent({ event, data: data.join('\n') })
      event = 'message'
      data = []
    } else if (!line.startsWith(':')) {
      const separator = line.indexOf(':')
      const field = separator < 0 ? line : line.slice(0, separator)
      let value = separator < 0 ? '' : line.slice(separator + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value || 'message'
      if (field === 'data') data.push(value)
    }
    line = ''
  }

  return {
    feed(text) {
      for (const character of text) {
        if (previousWasCR) {
          previousWasCR = false
          if (character === '\n') continue
        }
        if (character === '\r') {
          processLine()
          previousWasCR = true
        } else if (character === '\n') {
          processLine()
        } else {
          line += character
        }
      }
    },
  }
}

/** POST SSE transport; only an explicit `done` event means successful completion. */
export function createSSEConnection(url, body, handlers = {}) {
  const controller = new AbortController()
  let cancelled = false
  let completed = false
  let reader

  const finished = (async () => {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
      if (cancelled) return
      if (!response.ok) throw new Error(`请求失败（HTTP ${response.status}），请检查后端服务。`)
      if (!response.headers.get('content-type')?.includes('text/event-stream')) {
        throw new Error('服务未返回流式回答，请检查接口配置。')
      }
      if (!response.body) throw new Error('浏览器无法读取流式响应。')
      handlers.onOpen?.()
      reader = response.body.getReader()
      const decoder = new TextDecoder()
      const parser = createSSEParser(({ event, data }) => {
        if (cancelled || completed) return
        if (event === 'answer') handlers.onAnswer?.(data)
        if (event === 'sources') {
          let sources
          try {
            sources = JSON.parse(data)
          } catch {
            throw new Error('来源资料响应格式异常。')
          }
          if (!Array.isArray(sources) || sources.some((source) => !source || typeof source !== 'object' || Array.isArray(source))) {
            throw new Error('来源资料响应格式异常。')
          }
          handlers.onSources?.(sources)
        }
        if (event === 'error') throw new Error(data || '生成回答失败，请重试。')
        if (event === 'done') {
          completed = true
          handlers.onDone?.()
        }
      })

      while (!cancelled && !completed) {
        const result = await reader.read()
        parser.feed(decoder.decode(result.value, { stream: !result.done }))
        if (result.done) break
      }
      if (!cancelled && !completed) {
        throw new Error('回答连接中断，已收到的内容可能不完整，请重试。')
      }
    } catch (error) {
      if (!cancelled && !completed) handlers.onError?.(error)
    } finally {
      await reader?.cancel().catch(() => {})
      controller.abort()
    }
  })()

  return {
    finished,
    close() {
      cancelled = true
      controller.abort()
    },
  }
}

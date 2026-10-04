import assert from 'node:assert/strict'
import test from 'node:test'
import { createSSEConnection, createSSEParser } from '../src/api/sse.js'
import { renderMessageHtml } from '../src/utils/format.js'

function streamResponse(text, oneByteAtATime = false) {
  const bytes = new TextEncoder().encode(text)
  return new Response(new ReadableStream({
    start(controller) {
      if (oneByteAtATime) {
        for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
      } else {
        controller.enqueue(bytes)
      }
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } })
}

function mockFetch(t, implementation) {
  const original = globalThis.fetch
  globalThis.fetch = implementation
  t.after(() => { globalThis.fetch = original })
}

test('SSE parser preserves multiline data and whitespace across CRLF boundaries', () => {
  const events = []
  const parser = createSSEParser((event) => events.push(event))
  const input = ': heartbeat\r\nevent: answer\r\ndata: 第一行\r\ndata:  第二行\r\n\r\nevent: done\ndata:\n\n'
  for (const character of input) parser.feed(character)
  assert.deepEqual(events, [
    { event: 'answer', data: '第一行\n 第二行' },
    { event: 'done', data: '' },
  ])
})

test('POST stream decodes split UTF-8, receives sources and finishes only once', async (t) => {
  const answers = []
  const references = []
  let doneCount = 0
  const errors = []
  mockFetch(t, async (url, options) => {
    assert.equal(url, '/api/ai/chat/stream')
    assert.equal(options.method, 'POST')
    assert.deepEqual(JSON.parse(options.body), { message: '流程', chatId: 'test-id' })
    return streamResponse('event: answer\ndata: 你好\n\nevent: sources\ndata: [{"title":"项目说明","sourcePath":"项目.md"}]\n\nevent: done\ndata:\n\nevent: answer\ndata: 不应接收\n\n', true)
  })
  await createSSEConnection('/api/ai/chat/stream', { message: '流程', chatId: 'test-id' }, {
    onAnswer: (value) => answers.push(value),
    onSources: (value) => references.push(value),
    onDone: () => { doneCount += 1 },
    onError: (error) => errors.push(error),
  }).finished
  assert.deepEqual(answers, ['你好'])
  assert.equal(references[0][0].title, '项目说明')
  assert.equal(doneCount, 1)
  assert.deepEqual(errors, [])
})

test('a truncated response preserves partial answer and reports failure', async (t) => {
  let answer = ''
  let error
  let done = false
  mockFetch(t, async () => streamResponse('event: answer\ndata: 部分内容\n\n'))
  await createSSEConnection('/chat', {}, {
    onAnswer: (text) => { answer += text },
    onError: (value) => { error = value },
    onDone: () => { done = true },
  }).finished
  assert.equal(answer, '部分内容')
  assert.match(error.message, /中断/)
  assert.equal(done, false)
})

test('server error is terminal even if followed by a done event in the same chunk', async (t) => {
  let error
  let done = false
  mockFetch(t, async () => streamResponse('event: error\ndata: 知识库暂不可用\n\nevent: done\ndata:\n\n'))
  await createSSEConnection('/chat', {}, {
    onError: (value) => { error = value },
    onDone: () => { done = true },
  }).finished
  assert.equal(error.message, '知识库暂不可用')
  assert.equal(done, false)
})

test('malformed sources report protocol failure', async (t) => {
  let error
  mockFetch(t, async () => streamResponse('event: sources\ndata: {"title":"文档"}\n\n'))
  await createSSEConnection('/chat', {}, { onError: (value) => { error = value } }).finished
  assert.match(error.message, /格式异常/)
})

test('intentional cancellation suppresses late callbacks', async (t) => {
  let resolveFetch
  let callbacks = 0
  mockFetch(t, () => new Promise((resolve) => { resolveFetch = resolve }))
  const connection = createSSEConnection('/chat', {}, {
    onOpen: () => { callbacks += 1 },
    onAnswer: () => { callbacks += 1 },
    onDone: () => { callbacks += 1 },
    onError: () => { callbacks += 1 },
  })
  connection.close()
  resolveFetch(streamResponse('event: done\ndata:\n\n'))
  await connection.finished
  assert.equal(callbacks, 0)
})

test('answer formatting escapes document HTML and preserves basic text formatting', () => {
  const rendered = renderMessageHtml('<img src=x onerror=alert(1)>\n**说明** `命令`')
  assert.equal(rendered, '&lt;img src=x onerror=alert(1)&gt;<br/><strong>说明</strong> <code>命令</code>')
})

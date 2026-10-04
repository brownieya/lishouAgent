/**
 * 将纯文本消息内容转换为可安全渲染的 HTML 片段。
 * 先转义特殊字符防止 XSS，再支持极简的 **加粗** / `行内代码` / 换行展示，
 * 满足 AI 回复中常见的轻量排版需求。
 */
export function renderMessageHtml(text) {
  if (!text) return ''
  const escaped = String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  return escaped
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br/>')
}

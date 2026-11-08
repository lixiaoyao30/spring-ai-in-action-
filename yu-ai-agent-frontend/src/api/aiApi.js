// 直连后端，绕过 Vite 代理缓冲
const BASE_URL = 'http://localhost:8123/api/ai'

async function fetchSSE(url, onChunk, onDone, onError) {
  const controller = new AbortController()

  try {
    const response = await fetch(url, { signal: controller.signal })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const text = decoder.decode(value, { stream: true })
      const lines = text.split('\n')
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const chunk = line.slice(5)
          if (chunk !== '') onChunk(chunk)
        }
      }
    }
    onDone()
  } catch (err) {
    if (err.name !== 'AbortError') onError(err)
  }

  return { cancel: () => controller.abort() }
}

export const loveAppApi = {
  // 返回的每个 chunk 是一个文字碎片，调用方拼接到同一气泡
  chatSSE(message, chatId, onChunk, onDone, onError) {
    const params = new URLSearchParams({ message, chatId })
    return fetchSSE(`${BASE_URL}/love_app/chat/sse?${params}`, onChunk, onDone, onError)
  },
  chatSync(message, chatId) {
    const params = new URLSearchParams({ message, chatId })
    return fetch(`${BASE_URL}/love_app/chat/sync?${params}`).then(r => r.json())
  },
}

export const manusApi = {
  // 返回的每个 chunk 是智能体的一个完整步骤，调用方每步单独一个气泡
  chatSSE(message, onChunk, onDone, onError) {
    const params = new URLSearchParams({ message })
    return fetchSSE(`${BASE_URL}/manus/chat?${params}`, onChunk, onDone, onError)
  },
}

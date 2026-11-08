<template>
  <div class="chat-page">
    <!-- 顶栏 -->
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <div class="header-center">
        <span class="header-icon">💕</span>
        <span class="header-title">AI 恋爱大师</span>
      </div>
      <div class="chat-id-badge" :title="chatId">会话 ID: {{ shortChatId }}</div>
    </header>

    <!-- 消息列表 -->
    <main class="chat-body" ref="chatBodyRef">
      <div v-if="messages.length === 0" class="empty-hint">
        <p>💌 发送消息，开启你的恋爱咨询</p>
      </div>

      <div
        v-for="(msg, i) in messages"
        :key="i"
        class="msg-row"
        :class="msg.role === 'user' ? 'msg-row--right' : 'msg-row--left'"
      >
        <div v-if="msg.role === 'ai'" class="avatar avatar--ai">💕</div>
        <div class="bubble" :class="msg.role === 'user' ? 'bubble--user' : 'bubble--ai'">
          <span class="bubble-text" v-html="renderText(msg.content)"></span>
          <span v-if="msg.streaming" class="cursor-blink">▌</span>
        </div>
        <div v-if="msg.role === 'user'" class="avatar avatar--user">我</div>
      </div>
    </main>

    <!-- 输入区 -->
    <footer class="chat-footer">
      <textarea
        v-model="inputText"
        class="input-box"
        placeholder="输入你的问题，例如：我暗恋一个人，怎么表白？"
        rows="1"
        :disabled="isStreaming"
        @keydown.enter.exact.prevent="sendMessage"
      ></textarea>
      <button
        class="send-btn"
        :disabled="!inputText.trim() || isStreaming"
        @click="sendMessage"
      >
        {{ isStreaming ? '回复中...' : '发送' }}
      </button>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onUnmounted } from 'vue'
import { marked } from 'marked'
import { loveAppApi } from '../api/aiApi.js'

// 配置 marked：不转义 HTML，支持换行
marked.setOptions({ breaks: true })

const chatId = ref(generateChatId())
const messages = ref([])
const inputText = ref('')
const isStreaming = ref(false)
const chatBodyRef = ref(null)
let currentEventSource = null

const shortChatId = computed(() => chatId.value.slice(0, 8) + '...')

function generateChatId() {
  return 'chat-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9)
}

function renderText(text) {
  // 用 marked 把 Markdown 转成 HTML，**加粗**、*斜体*、换行都能正确显示
  return marked.parse(text || '')
}

async function scrollToBottom() {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

// 打字机队列
let typeQueue = []
let isTyping = false

function startTyping(msgIndex) {
  if (isTyping) return
  isTyping = true
  function tick() {
    if (typeQueue.length === 0) {
      isTyping = false
      return
    }
    // 通过 messages.value[index] 修改，Vue 能感知到响应式变化
    messages.value[msgIndex].content += typeQueue.shift()
    scrollToBottom()
    setTimeout(tick, 30)
  }
  tick()
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isStreaming.value) return

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  await scrollToBottom()

  messages.value.push({ role: 'ai', content: '', streaming: true })
  const msgIndex = messages.value.length - 1
  isStreaming.value = true
  typeQueue = []
  isTyping = false

  currentEventSource = loveAppApi.chatSSE(
    text,
    chatId.value,
    (chunk) => {
      for (const char of chunk) {
        typeQueue.push(char)
      }
      startTyping(msgIndex)
    },
    // onDone：等队列里的字全部打完再关闭 streaming
    () => {
      const waitTypeDone = setInterval(() => {
        if (!isTyping && typeQueue.length === 0) {
          clearInterval(waitTypeDone)
          messages.value[msgIndex].streaming = false
          isStreaming.value = false
          currentEventSource = null
          scrollToBottom()
        }
      }, 50)
    },
    () => {
      typeQueue = []
      isTyping = false
      messages.value[msgIndex].streaming = false
      isStreaming.value = false
      currentEventSource = null
      if (!messages.value[msgIndex].content) {
        messages.value[msgIndex].content = '⚠️ 连接失败，请稍后重试。'
      }
      scrollToBottom()
    }
  )
}

onUnmounted(() => {
  typeQueue = []
  currentEventSource?.cancel?.()
})
</script>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f0f2f5;
}

/* 顶栏 */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 60px;
  background: linear-gradient(135deg, #ff6b9d, #c44569);
  color: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,.15);
  flex-shrink: 0;
}

.back-btn {
  background: rgba(255,255,255,.2);
  border: none;
  color: #fff;
  padding: 6px 14px;
  border-radius: 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: background .2s;
}
.back-btn:hover { background: rgba(255,255,255,.35); }

.header-center {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 1.15rem;
  font-weight: 600;
}
.header-icon { font-size: 1.3rem; }

.chat-id-badge {
  font-size: 0.75rem;
  background: rgba(255,255,255,.2);
  padding: 4px 10px;
  border-radius: 12px;
  cursor: default;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 消息区 */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.empty-hint {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 1rem;
}

.msg-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}
.msg-row--right { flex-direction: row-reverse; }

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
  flex-shrink: 0;
}
.avatar--ai { background: #fce4ec; }
.avatar--user {
  background: linear-gradient(135deg, #ff6b9d, #c44569);
  color: #fff;
  font-size: 0.85rem;
  font-weight: 600;
}

.bubble {
  max-width: 68%;
  padding: 12px 16px;
  border-radius: 18px;
  font-size: 0.95rem;
  line-height: 1.6;
  word-break: break-word;
}
.bubble--ai {
  background: #fff;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 6px rgba(0,0,0,.06);
  color: #222;
}
/* Markdown 元素样式 */
.bubble--ai :deep(p) { margin: 0 0 6px; }
.bubble--ai :deep(p:last-child) { margin-bottom: 0; }
.bubble--ai :deep(strong) { font-weight: 600; color: #c44569; }
.bubble--ai :deep(em) { font-style: italic; }
.bubble--ai :deep(ul), .bubble--ai :deep(ol) { padding-left: 18px; margin: 4px 0; }
.bubble--ai :deep(li) { margin: 2px 0; }
.bubble--ai :deep(code) { background: #f5f5f5; padding: 1px 5px; border-radius: 4px; font-size: 0.88em; }
.bubble--user {
  background: linear-gradient(135deg, #ff6b9d, #c44569);
  border-bottom-right-radius: 4px;
  color: #fff;
}

.cursor-blink {
  display: inline-block;
  animation: blink .8s step-end infinite;
  color: #ff6b9d;
  margin-left: 2px;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* 输入区 */
.chat-footer {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 14px 16px;
  background: #fff;
  box-shadow: 0 -2px 8px rgba(0,0,0,.06);
  flex-shrink: 0;
}

.input-box {
  flex: 1;
  border: 1.5px solid #e0e0e0;
  border-radius: 22px;
  padding: 10px 18px;
  font-size: 0.95rem;
  resize: none;
  outline: none;
  transition: border-color .2s;
  max-height: 120px;
  line-height: 1.5;
  font-family: inherit;
}
.input-box:focus { border-color: #ff6b9d; }
.input-box:disabled { background: #f5f5f5; cursor: not-allowed; }

.send-btn {
  padding: 10px 24px;
  background: linear-gradient(135deg, #ff6b9d, #c44569);
  color: #fff;
  border: none;
  border-radius: 22px;
  font-size: 0.95rem;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity .2s;
  flex-shrink: 0;
}
.send-btn:hover:not(:disabled) { opacity: .85; }
.send-btn:disabled { opacity: .5; cursor: not-allowed; }
</style>

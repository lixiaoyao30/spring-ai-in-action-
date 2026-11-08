<template>
  <div class="chat-page">
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <div class="header-center">
        <span class="header-icon">🧠</span>
        <span class="header-title">AI 超级智能体</span>
      </div>
      <div class="status-badge" :class="isStreaming ? 'status-badge--active' : ''">
        {{ isStreaming ? '⚡ 执行中' : '● 就绪' }}
      </div>
    </header>

    <main class="chat-body" ref="chatBodyRef">
      <div v-if="sessions.length === 0" class="empty-hint">
        <div class="empty-icon">🚀</div>
        <p>发送任务，让 AI Agent 为你完成复杂任务</p>
        <p class="hint-example">例：爬取 https://spring.io 首页并总结内容</p>
      </div>

      <!-- 每次对话是一个 session -->
      <div v-for="(session, si) in sessions" :key="si" class="session">

        <!-- 用户消息 -->
        <div class="msg-row msg-row--right">
          <div class="bubble bubble--user">{{ session.question }}</div>
          <div class="avatar avatar--user">我</div>
        </div>

        <!-- 思考过程折叠面板（有步骤才显示） -->
        <div v-if="session.steps.length > 0" class="thinking-block">
          <button class="thinking-toggle" @click="session.expanded = !session.expanded">
            <span class="thinking-icon">{{ session.expanded ? '▾' : '▸' }}</span>
            <span class="thinking-label">
              {{ session.streaming ? '⚙️ 思考中...' : `已完成 ${session.steps.length} 个步骤` }}
            </span>
            <span v-if="session.streaming" class="thinking-spinner"></span>
          </button>

          <div v-show="session.expanded" class="thinking-steps">
            <div
              v-for="(step, idx) in session.steps"
              :key="idx"
              class="step-row"
            >
              <div class="step-dot" :class="idx === session.steps.length - 1 && session.streaming ? 'step-dot--active' : 'step-dot--done'"></div>
              <div class="step-content">
                <span class="step-num">步骤 {{ idx + 1 }}</span>
                <span class="step-text" v-html="renderText(step)"></span>
              </div>
            </div>
          </div>
        </div>

        <!-- 最终答案气泡（流结束后显示） -->
        <div v-if="session.answer" class="msg-row msg-row--left">
          <div class="avatar avatar--ai">🧠</div>
          <div class="bubble bubble--ai" v-html="renderText(session.answer)"></div>
        </div>

        <!-- 执行中占位 -->
        <div v-else-if="session.streaming" class="msg-row msg-row--left">
          <div class="avatar avatar--ai">🧠</div>
          <div class="bubble bubble--ai bubble--loading">
            <span class="dot-flashing"></span>
          </div>
        </div>

      </div>
    </main>

    <footer class="chat-footer">
      <textarea
        v-model="inputText"
        class="input-box"
        placeholder="输入任务，例如：爬取 https://codefather.cn 首页并总结"
        rows="1"
        :disabled="isStreaming"
        @keydown.enter.exact.prevent="sendMessage"
      ></textarea>
      <button class="send-btn" :disabled="!inputText.trim() || isStreaming" @click="sendMessage">
        {{ isStreaming ? '执行中...' : '执行' }}
      </button>
    </footer>
  </div>
</template>

<script setup>
import { ref, nextTick, onUnmounted } from 'vue'
import { marked } from 'marked'
import { manusApi } from '../api/aiApi.js'

marked.setOptions({ breaks: true })

// sessions：每次对话一个对象，包含 question / steps / answer / streaming / expanded
const sessions = ref([])
const inputText = ref('')
const isStreaming = ref(false)
const chatBodyRef = ref(null)
let currentEventSource = null

function renderText(text) {
  return marked.parse(text || '')
}

async function scrollToBottom() {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isStreaming.value) return

  inputText.value = ''
  isStreaming.value = true

  // 新建一个会话对象
  const session = {
    question: text,
    steps: [],
    answer: '',
    streaming: true,
    expanded: true,   // 执行中默认展开步骤
  }
  sessions.value.push(session)
  await scrollToBottom()

  currentEventSource = manusApi.chatSSE(
    text,
    // onChunk：每个 chunk 是 Agent 的一个步骤
    (chunk) => {
      if (!chunk.trim()) return
      session.steps.push(chunk)
      scrollToBottom()
    },
    // onDone：流结束，最后一个步骤作为最终答案，折叠思考过程
    () => {
      if (session.steps.length > 0) {
        // 最后一步作为最终答案展示
        session.answer = session.steps[session.steps.length - 1]
      }
      session.streaming = false
      session.expanded = false  // 完成后折叠思考过程
      isStreaming.value = false
      currentEventSource = null
      scrollToBottom()
    },
    // onError
    () => {
      session.answer = '⚠️ 执行失败，请稍后重试。'
      session.streaming = false
      isStreaming.value = false
      currentEventSource = null
      scrollToBottom()
    }
  )
}

onUnmounted(() => {
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
  background: linear-gradient(135deg, #4776e6, #8e54e9);
  color: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,.15);
  flex-shrink: 0;
}
.back-btn {
  background: rgba(255,255,255,.2);
  border: none; color: #fff;
  padding: 6px 14px; border-radius: 20px;
  cursor: pointer; font-size: 0.9rem;
  transition: background .2s;
}
.back-btn:hover { background: rgba(255,255,255,.35); }
.header-center { display: flex; align-items: center; gap: 8px; font-size: 1.15rem; font-weight: 600; }
.header-icon { font-size: 1.3rem; }
.status-badge {
  font-size: 0.8rem; background: rgba(255,255,255,.2);
  padding: 4px 12px; border-radius: 12px;
}
.status-badge--active { background: rgba(255,220,50,.3); animation: pulse 1.5s ease-in-out infinite; }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.6} }

/* 消息区 */
.chat-body {
  flex: 1; overflow-y: auto;
  padding: 24px 20px;
  display: flex; flex-direction: column; gap: 24px;
}
.empty-hint {
  flex: 1; display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  color: #999; gap: 10px;
}
.empty-icon { font-size: 2.5rem; }
.hint-example {
  font-size: 0.85rem; background: #fff;
  padding: 6px 16px; border-radius: 20px; color: #aaa;
}

/* session 区块 */
.session { display: flex; flex-direction: column; gap: 10px; }

.msg-row { display: flex; align-items: flex-end; gap: 10px; }
.msg-row--right { flex-direction: row-reverse; }

.avatar {
  width: 38px; height: 38px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 1.1rem; flex-shrink: 0;
}
.avatar--ai { background: #ede7f6; }
.avatar--user {
  background: linear-gradient(135deg, #4776e6, #8e54e9);
  color: #fff; font-size: 0.85rem; font-weight: 600;
}

.bubble {
  max-width: 72%; padding: 12px 16px;
  border-radius: 18px; font-size: 0.95rem;
  line-height: 1.6; word-break: break-word;
}
.bubble--user {
  background: linear-gradient(135deg, #4776e6, #8e54e9);
  border-bottom-right-radius: 4px; color: #fff;
}
.bubble--ai {
  background: #fff;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 8px rgba(0,0,0,.07);
  color: #1a1a1a;
}
.bubble--ai :deep(p) { margin: 0 0 6px; }
.bubble--ai :deep(p:last-child) { margin-bottom: 0; }
.bubble--ai :deep(strong) { font-weight: 600; color: #4776e6; }
.bubble--ai :deep(ul), .bubble--ai :deep(ol) { padding-left: 18px; margin: 4px 0; }
.bubble--ai :deep(li) { margin: 2px 0; }
.bubble--ai :deep(code) { background: #f0f4ff; padding: 1px 5px; border-radius: 4px; font-size: 0.88em; }

/* 加载中气泡 */
.bubble--loading {
  padding: 16px 20px;
  min-width: 60px; display: flex; align-items: center;
}
.dot-flashing {
  width: 8px; height: 8px; border-radius: 50%;
  background: #8e54e9;
  animation: dotFlash 1.2s infinite;
  box-shadow: 16px 0 0 #8e54e9, 32px 0 0 #8e54e9;
}
@keyframes dotFlash {
  0%,80%,100% { opacity: .2; transform: scale(.8); }
  40% { opacity: 1; transform: scale(1); }
}

/* 思考过程折叠面板 */
.thinking-block {
  margin-left: 48px;
  border: 1px solid #e8e0f5;
  border-radius: 12px;
  background: #faf8ff;
  overflow: hidden;
}
.thinking-toggle {
  width: 100%; display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  background: none; border: none; cursor: pointer;
  font-size: 0.88rem; color: #7c5cbf;
  text-align: left; transition: background .15s;
}
.thinking-toggle:hover { background: #f0e8ff; }
.thinking-icon { font-size: 0.8rem; width: 12px; }
.thinking-label { flex: 1; font-weight: 500; }
.thinking-spinner {
  width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid #c9a9ff;
  border-top-color: #8e54e9;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.thinking-steps {
  border-top: 1px solid #e8e0f5;
  padding: 12px 14px;
  display: flex; flex-direction: column; gap: 10px;
}
.step-row { display: flex; gap: 10px; align-items: flex-start; }
.step-dot {
  width: 8px; height: 8px; border-radius: 50%;
  margin-top: 6px; flex-shrink: 0;
}
.step-dot--done { background: #b39ddb; }
.step-dot--active { background: #8e54e9; animation: pulse 1s ease-in-out infinite; }
.step-content { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.step-num {
  font-size: 0.72rem; font-weight: 600;
  color: #9c6fce; margin-bottom: 2px;
}
.step-text { font-size: 0.88rem; color: #555; line-height: 1.5; }

/* 输入区 */
.chat-footer {
  display: flex; align-items: flex-end; gap: 10px;
  padding: 14px 16px; background: #fff;
  box-shadow: 0 -2px 8px rgba(0,0,0,.06); flex-shrink: 0;
}
.input-box {
  flex: 1; border: 1.5px solid #e0e0e0; border-radius: 22px;
  padding: 10px 18px; font-size: 0.95rem;
  resize: none; outline: none;
  transition: border-color .2s; max-height: 120px;
  line-height: 1.5; font-family: inherit;
}
.input-box:focus { border-color: #8e54e9; }
.input-box:disabled { background: #f5f5f5; cursor: not-allowed; }
.send-btn {
  padding: 10px 24px;
  background: linear-gradient(135deg, #4776e6, #8e54e9);
  color: #fff; border: none; border-radius: 22px;
  font-size: 0.95rem; font-weight: 500; cursor: pointer;
  white-space: nowrap; transition: opacity .2s; flex-shrink: 0;
}
.send-btn:hover:not(:disabled) { opacity: .85; }
.send-btn:disabled { opacity: .5; cursor: not-allowed; }
</style>

<template>
  <div class="home">
    <div class="hero">
      <h1 class="title">🤖 Yu AI Agent</h1>
      <p class="subtitle">选择一个 AI 应用开始体验</p>
    </div>

    <div class="card-grid">
      <div class="app-card" @click="$router.push('/love-app')">
        <div class="card-icon">💕</div>
        <h2 class="card-title">AI 恋爱大师</h2>
        <p class="card-desc">专业恋爱顾问，解答感情困惑，提供恋爱建议</p>
        <button class="card-btn">立即体验 →</button>
      </div>

      <div class="app-card" @click="$router.push('/manus')">
        <div class="card-icon">🧠</div>
        <h2 class="card-title">AI 超级智能体</h2>
        <p class="card-desc">强大的 AI Agent，能够搜索信息、分析问题、完成复杂任务</p>
        <button class="card-btn">立即体验 →</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.hero {
  text-align: center;
  margin-bottom: 48px;
}

.title {
  font-size: 3rem;
  font-weight: 700;
  color: #fff;
  margin-bottom: 12px;
  text-shadow: 0 2px 8px rgba(0,0,0,.2);
}

.subtitle {
  font-size: 1.2rem;
  color: rgba(255,255,255,.85);
}

.card-grid {
  display: flex;
  gap: 28px;
  flex-wrap: wrap;
  justify-content: center;
}

.app-card {
  background: #fff;
  border-radius: 20px;
  padding: 40px 36px;
  width: 320px;
  cursor: pointer;
  transition: transform .2s, box-shadow .2s;
  box-shadow: 0 8px 32px rgba(0,0,0,.12);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}

.app-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 16px 48px rgba(0,0,0,.18);
}

.card-icon {
  font-size: 3rem;
  line-height: 1;
}

.card-title {
  font-size: 1.4rem;
  font-weight: 600;
  color: #2d2d2d;
}

.card-desc {
  font-size: 0.95rem;
  color: #666;
  line-height: 1.6;
}

.card-btn {
  margin-top: 8px;
  padding: 10px 28px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border: none;
  border-radius: 24px;
  font-size: 0.95rem;
  font-weight: 500;
  cursor: pointer;
  transition: opacity .2s;
}

.card-btn:hover {
  opacity: .85;
}
</style>

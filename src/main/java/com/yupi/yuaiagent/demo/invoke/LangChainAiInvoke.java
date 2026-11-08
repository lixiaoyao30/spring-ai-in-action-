package com.yupi.yuaiagent.demo.invoke;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * 【接入方式四：LangChain4j 框架调用】
 *
 * ─────────────────────────────────────────────────────────────
 * 四种接入方式对比：
 *
 *   SdkAiTextInvoke    → 阿里 DashScope SDK，约 20 行，语言受限
 *   HttpAiInvoke       → Hutool HTTP 直接请求，约 25 行，最灵活
 *   SpringAiInvoke     → Spring AI，3 行核心代码，换模型只改 yml
 *   LangChainAiInvoke  → LangChain4j，1 行调用，功能最完整
 *
 * LangChain4j 的核心价值：
 *   ✅ RAG（检索增强生成）：让 AI 读你的文档来回答问题
 *   ✅ AI Agent：让 AI 自主规划并调用工具完成任务
 *   ✅ 工具调用（Function Calling）：AI 调用你的 Java 方法
 *   ✅ 对话记忆：自动管理多轮对话上下文
 *   适合构建复杂 AI 应用，Spring AI 更适合简单接入
 * ─────────────────────────────────────────────────────────────
 *
 * 为什么用 OpenAiChatModel 连通义千问？
 *   通义千问支持 OpenAI 兼容协议（改 baseUrl 即可），路径格式：
 *   https://{workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
 *   这样不需要额外引入 dashscope community 模块
 *
 * ChatModel 是什么？
 *   LangChain4j 1.0.0 定义的"能聊天的模型"接口
 *   （旧版叫 ChatLanguageModel，1.0.0 正式版改名为 ChatModel）
 *   chat(String message) → 发送一条消息 → 直接返回字符串回复
 * ─────────────────────────────────────────────────────────────
 */
public class LangChainAiInvoke {

    public static void main(String[] args) {
        // ── 第一步：构建模型对象 ──────────────────────────────────
        // OpenAiChatModel.builder() 建造者模式，逐项配置：
        //
        //   baseUrl   → 通义千问 OpenAI 兼容接口根路径
        //               固定格式：https://{workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
        //               LangChain4j 会自动在后面拼 /chat/completions
        //               ⚠️ 不能用 TestApiKey.BASE_URL（那是 DashScope 原生路径 /api/v1，协议不同）
        //
        //   apiKey    → 鉴权密钥（和 DashScope SDK / Spring AI 用同一个 key）
        //
        //   modelName → 模型名称（qwen-max / qwen-plus / qwen-turbo）
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl("https://ws-wh2whu9gc711wu6e.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
                .apiKey(TestApiKey.API_KEY)
                .modelName("qwen-max")
                .build();

        // ── 第二步：发消息，直接拿字符串回复 ─────────────────────
        // chat(String) 直接返回字符串，比 Spring AI 更简洁：
        //   Spring AI：call(prompt).getResult().getOutput().getText()  → 4 步
        //   LangChain4j：chat(message)                                 → 1 步
        String answer = chatModel.chat("我是程序员鱼皮，这是编程导航 codefather.cn 的 AI 超级智能体原创项目");

        System.out.println(answer);
    }
}

package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.app.LoveApp;
import com.yupi.yuaiagent.common.BaseResponse;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * AI 功能接口控制器
 *
 * @RestController = @Controller + @ResponseBody
 *   普通 @Controller 返回"页面名"（跳转 HTML），
 *   @RestController 直接把方法返回值序列化成 JSON/文本发给前端，不跳转页面。
 *
 * @RequestMapping("/ai")
 *   类级别的路径前缀，类下所有接口 URL 都以 /ai 开头。
 *   例：类上 /ai + 方法上 /manus/chat → 完整路径 /ai/manus/chat
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    /**
     * @Resource：依赖注入注解（来自 jakarta / javax）
     * Spring 启动时自动把容器中已有的 LoveApp 实例赋值到这个字段，
     * 无需手动 new，实现"控制反转（IoC）"。
     */
    @Resource
    private LoveApp loveApp;

    /**
     * 工具箱数组：存放 Agent 可调用的所有工具（搜索/爬虫/下载/PDF 等）。
     * ToolCallback[] 是数组类型，[] 表示"一组同类对象"。
     * 由 ToolRegistration.allTools() @Bean 统一创建并注入。
     */
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 对话模型（OpenAI 兼容协议）：用于 YuManus Agent 的底层模型。
     * 容器中同时存在 dashScopeChatModel 和 openAiChatModel 两个 ChatModel Bean，
     * @Qualifier 精确指定注入哪一个，避免 Spring 因"类型相同、不知选谁"而启动报错。
     */
    @Resource
    @Qualifier("openAiChatModel")
    private ChatModel openAiChatModel;

    /**
     * 同步调用 AI 恋爱大师
     *
     * 同步 = 等 AI 全部回答完毕，再一次性返回完整字符串。
     * 适合：内容较短、对延迟不敏感的场景。
     * 缺点：用户需等待全部生成完才能看到内容。
     *
     * GET /ai/love_app/chat/sync?message=你好&chatId=abc
     *
     * @param message 用户输入的消息
     * @param chatId  会话 ID，用于区分不同对话（多轮记忆）
     * @return AI 回复的完整文本
     */
    @GetMapping("/love_app/chat/sync")
    public BaseResponse<String> doChatWithLoveAppSync(String message, String chatId) {
        try {
            return BaseResponse.success(loveApp.doChat(message, chatId));
        } catch (Exception e) {
            return BaseResponse.error(500, "AI 服务异常：" + e.getMessage());
        }
    }

    /**
     * 流式调用 AI 恋爱大师 —— 方式一：直接返回 Flux
     * Flux<String>：响应式数据流，AI 生成一个字就推送一个字，像打字机效果。
     *   类比：List 是"一箱货物一次性送达"，Flux 是"快递员每件单独送"。
     * produces = MediaType.TEXT_EVENT_STREAM_VALUE（即 "text/event-stream"）：
     *   告诉浏览器"这是 SSE 流，请保持连接持续接收"，而不是接收一次就断开。
     * GET /ai/love_app/chat/sse?message=你好&chatId=abc
     *
     * @param message 用户输入的消息
     * @param chatId  会话 ID
     * @return Flux 数据流，逐字推送 AI 回复
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }

    /**
     * 流式调用 AI 恋爱大师 —— 方式二：返回带元数据的 ServerSentEvent
     *
     * 与方式一（Flux<String>）的区别：
     *   方式一只发纯文字：        data: 你好
     *   方式二每条带"快递单"：    id: 1 \n event: message \n data: 你好
     *
     * 多了"快递单"有什么用：
     *   ① 断线重连：浏览器断线重连时自动带上 Last-Event-ID，服务器从断点续发，不重复
     *   ② 多事件类型：同一连接可发 event:message（正文）/ event:error（报错）等不同类型
     *
     * .map()：对流中每个元素做转换，把 String 包装成 ServerSentEvent。
     *   chunk -> ... 是 Lambda，箭头左边=输入，右边=处理逻辑。
     *
     * 简单场景用方式一，生产环境需要断点续传或多事件类型时用方式二。
     *
     * GET /ai/love_app/chat/server_sent_event?message=你好&chatId=abc
     *
     * @param message 用户输入的消息
     * @param chatId  会话 ID
     * @return 携带元数据的 SSE 数据流
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                // map：对流中每个元素做转换，相当于流水线上的"包装机"
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)  // 把字符串片段放入 SSE 数据包
                        .build());
    }

    /**
     * 流式调用 AI 恋爱大师 —— 方式三：手动控制 SseEmitter
     *
     * SseEmitter：Spring MVC（Servlet 体系）的 SSE 推送器，可手动控制何时发送/结束/报错。
     *   与 Flux 的区别：Flux 是响应式体系（WebFlux），SseEmitter 是传统 Servlet 体系。
     *
     * 执行顺序：
     *   ① new SseEmitter()  → 创建推送通道（此时浏览器还没收到任何数据）
     *   ② .subscribe(...)   → 订阅 Flux，触发 AI 开始生成，每来一个字就 send() 推送
     *   ③ return sseEmitter → 立刻把通道返回给浏览器（AI 还在后台生成中）
     *
     * .subscribe() 三个参数（Lambda / 方法引用）：
     *   chunk -> sseEmitter.send(chunk)  → 每收到一个数据片段，推送给浏览器
     *   sseEmitter::completeWithError    → 出现异常时，通知浏览器出错并关闭连接
     *   sseEmitter::complete             → 全部数据发送完毕，正常关闭连接
     *
     * sseEmitter::completeWithError 是方法引用写法，等价于 Lambda：
     *   error -> sseEmitter.completeWithError(error)
     *
     * GET /ai/love_app/chat/sse_emitter?message=你好&chatId=abc
     *
     * @param message 用户输入的消息
     * @param chatId  会话 ID
     * @return SseEmitter 推送通道
     */
    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        // 创建 SseEmitter，超时时间 180000ms = 3 分钟
        // 超时后浏览器连接自动断开，防止连接永久占用服务器资源
        SseEmitter sseEmitter = new SseEmitter(180000L);

        // 订阅 Flux 流：触发 AI 开始生成，将每个字符片段实时推送给浏览器
        loveApp.doChatByStream(message, chatId)
                .subscribe(
                        chunk -> {
                            try {
                                sseEmitter.send(chunk); // 每个片段立即推送给浏览器
                            } catch (IOException e) {
                                sseEmitter.completeWithError(e); // 推送失败则关闭连接
                            }
                        },
                        sseEmitter::completeWithError, // 上游 Flux 异常 → 关闭并报错
                        sseEmitter::complete           // 上游 Flux 完成 → 正常关闭
                );

        // 立即返回 SseEmitter，浏览器建立连接后持续接收后台推送的数据
        return sseEmitter;
    }

    /**
     * 流式调用 YuManus 超级智能体
     *
     * 为什么每次请求都 new YuManus，而不用 @Resource 注入单例？
     *   YuManus 内部维护 messageList（对话历史），是有状态的对象。
     *   若用单例，多个用户共享同一个 messageList，会导致对话记录互相污染。
     *   每次 new 保证每个请求拥有独立的对话上下文，互不干扰。
     *
     * runStream() 内部使用 CompletableFuture 异步线程 + SseEmitter：
     *   Agent 每完成一个 ReAct 步骤（think + act），立即推送一次结果给浏览器，
     *   用户可实时看到 Agent 的思考和工具调用过程。
     *
     * GET /ai/manus/chat?message=帮我搜索上海天气
     *
     * @param message 用户输入的任务描述
     * @return SseEmitter 推送通道，逐步推送 Agent 每步执行结果
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        // 每次请求创建新实例，确保对话上下文（messageList）相互隔离
        YuManus yuManus = new YuManus(allTools, openAiChatModel);
        return yuManus.runStream(message);
    }
}

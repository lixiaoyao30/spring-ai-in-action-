package com.yupi.yuaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * ═══════════════════════════════════════════════════════
 * MyLoggerAdvisor — 自定义日志 Advisor
 * ═══════════════════════════════════════════════════════
 *
 * 【Advisor 是什么？】
 *   Advisor = 拦截器，在 AI 请求的前后插入自定义逻辑。
 *   类比：快递的"发货前检查"和"签收后记录"。
 *
 * 【before / after 什么时候执行？谁调用的？】
 *
 *   你调用 chatClient.prompt()...call()
 *                              ↑
 *                         这一行是扳机
 *                              │
 *          Spring AI 框架在内部自动执行：
 *                              │
 *          ① 顺序执行所有 Advisor 的 before()
 *             MyLoggerAdvisor 排第 3，所以第 3 个执行 before
 *                              │
 *          ② 发送给 AI，等待响应
 *                              │
 *          ③ 逆序执行所有 Advisor 的 after()
 *             MyLoggerAdvisor 排第 3（倒数第 1），所以第 1 个执行 after
 *
 *   你不需要手动调 before/after，框架全权负责。
 *
 * 【为什么 after 是逆序？洋葱模型】
 *
 *   Advisor 链像洋葱，外层先 before，内层后 before；
 *   返回时内层先 after，外层后 after：
 *
 *   before 顺序：  1(记忆) → 2(敏感词) → 3(日志) → AI → 3(日志) → 2(敏感词) → 1(记忆) :after 逆序
 *
 *   这样最外层的 MessageChatMemoryAdvisor 能在 after 最后执行，
 *   确保所有处理都完成后再持久化记忆。
 *
 * 【getOrder() 返回 1 是什么意思？】
 *   order 值越小，Advisor 在链中排越靠外（越先 before，越后 after）。
 *   本类 order=1，排在最内层附近（日志记录应该贴近 AI 调用处）。
 */
@Slf4j
public class MyLoggerAdvisor implements BaseAdvisor {

    /**
     * before：发送给 AI 之前执行
     * 职责：打印本次请求包含哪些消息（系统提示 + 历史 + 用户消息）
     *
     * 注意：此时 MessageChatMemoryAdvisor 已经执行过 before，
     * 历史消息已注入到 request 里，所以能看到完整消息列表。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        log.info("[MyLogger] 请求消息: {}", request.prompt().getInstructions());
        return request; // 必须返回 request（可以修改后返回，这里原样返回）
    }

    /**
     * after：AI 响应返回后执行
     * 职责：打印 token 用量（计费监控）和 AI 回复内容
     *
     * 注意：此时 MessageChatMemoryAdvisor 的 after 还没执行，
     * 记忆还没存入 Redis，但 AI 的回复已经拿到了。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response.chatResponse() == null) return response;

        var usage = response.chatResponse().getMetadata().getUsage();
        log.info("[MyLogger] token → 输入:{} 输出:{} 合计:{}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());

        log.info("[MyLogger] AI 响应: {}",
                response.chatResponse().getResult().getOutput().getText());

        return response; // 必须返回 response，后续 Advisor 和调用方还要用
    }

    /**
     * 排序值：值越小排越外层（越先 before，越后 after）
     * 日志 Advisor 排靠内（贴近 AI 调用处），所以给一个较小正数
     */
    @Override
    public int getOrder() {
        return 1;
    }
}

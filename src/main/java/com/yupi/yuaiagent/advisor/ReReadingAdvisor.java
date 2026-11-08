package com.yupi.yuaiagent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;

/**
 * Re-Reading Advisor（Re2）
 *
 * 原理：把用户原始问题按模板格式化，让 AI 读两遍，提升推理准确率。
 * 改写格式
 *   {re2_input_query}
 *   Read the question again: {re2_input_query}
 *
 * 实现思路（对齐截图旧版逻辑，用 1.1.2 新 API 实现）：
 *   1. 取出用户原始问题，存入参数 map（key = "re2_input_query"）
 *   2. 用 PromptTemplate 把模板里的占位符替换成实际问题
 *   3. 用改写后的内容替换原始用户消息
 *
 * 适用场景：数学题、逻辑推理、多步骤问题
 * ⚠️ token 消耗加倍，C 端高并发场景慎用
 */
public class ReReadingAdvisor implements BaseAdvisor {

    // Re2 Prompt 模板：{re2_input_query} 是占位符，运行时替换成用户实际问题
    private static final PromptTemplate RE2_PROMPT_TEMPLATE = new PromptTemplate("""
            {re2_input_query}
            Read the question again: {re2_input_query}
            """);

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // ① 取出用户原始问题
        String originalQuery = request.prompt().getUserMessage().getText();

        // ② 把原始问题填入模板占位符，生成 Re2 格式的新问题
        //    等价于截图里：advisedUserParams.put("re2_input_query", userText)
        //                  .userText("{re2_input_query}\nRead the question again: {re2_input_query}")
        String re2Query = RE2_PROMPT_TEMPLATE
                .render(java.util.Map.of("re2_input_query", originalQuery));

        // ③ 用改写后的问题替换原始用户消息，返回新 request（原 request 不可变）
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(
                        msg -> new UserMessage(re2Query)))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // Re2 只改写请求，响应不处理，直接透传
        return response;
    }

    @Override
    public int getOrder() {
        // 在记忆 Advisor（order=0）之后执行，改写的是注入记忆后的最终消息
        return 2;
    }
}

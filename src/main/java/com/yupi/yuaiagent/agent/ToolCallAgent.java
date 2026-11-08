package com.yupi.yuaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ToolCallAgent — 工具调用 Agent（第3层，核心实现）
 *
 * ═══════════════════════════════════════════════════════
 * 【这个类的职责】
 * ═══════════════════════════════════════════════════════
 *
 *   实现了 ReActAgent 声明的两个抽象方法：
 *     think()：向 AI 发消息，让 AI 决定调哪些工具
 *     act()：把 AI 选的工具真正执行，结果存入消息历史
 *
 *   同时管理三个核心对象：
 *     availableTools    → 可用工具数组（6个：文件/搜索/爬虫/下载/终端/PDF）
 *     toolCallingManager→ 工具执行管理器（负责真正调用工具方法）
 *     chatOptions       → Chat 选项（关键：禁用 Spring AI 自动执行工具）
 *
 * ═══════════════════════════════════════════════════════
 * 【Function Calling（工具调用）完整流程】
 * ═══════════════════════════════════════════════════════
 *
 *   第1步（think 里）：
 *     发给 AI：messageList + .tools(availableTools)
 *     tools(availableTools) 把6个工具的 JSON Schema 一起发给 AI
 *     AI 回复：我要调 webSearch({"query": "上海天气"})
 *
 *   第2步（think 里）：
 *     解析 AI 回复里的 toolCallList（工具调用指令列表）
 *     toolCallList 不为空 → return true（告诉 step() 去 act）
 *
 *   第3步（act 里）：
 *     toolCallingManager.executeToolCalls() 执行工具
 *     结果自动加入 conversationHistory（消息历史）
 *
 *   第4步（下一轮 think 里）：
 *     AI 看到工具结果，继续推理，直到调 terminate 或达到 maxSteps
 *
 * ═══════════════════════════════════════════════════════
 * 【.tools() 和 .toolCallbacks() 的区别（重要！）】
 * ═══════════════════════════════════════════════════════
 *
 *   .tools(Object...)           → 传入原始 Java 对象（如 new FileOperationTool()）
 *                                  Spring AI 内部会扫描对象上的 @Tool 注解方法
 *
 *   .toolCallbacks(ToolCallback[]) → 传入已封装好的 ToolCallback 对象
 *                                  ToolCallbacks.from(new FileOperationTool()) 的结果
 *                                  Spring AI 直接使用，不再二次扫描 @Tool
 *
 *   ToolRegistration.allTools() 用 ToolCallbacks.from() 预先封装了所有工具，
 *   所以这里必须用 .toolCallbacks()，而不是 .tools()。
 *   用错了会报：No @Tool annotated methods found in MethodToolCallback{...}
 *
 * ═══════════════════════════════════════════════════════
 * 【为什么要禁用 Spring AI 内置工具执行？】
 * ═══════════════════════════════════════════════════════
 *
 *   Spring AI 默认行为：AI 返回工具调用 → Spring AI 自动执行 → 再次调 AI → 返回最终答案
 *   这个自动流程把"思考"和"行动"合并了，无法单独控制每一步。
 *
 *   ToolCallAgent 需要手动控制：
 *     - think()：只让 AI 决定调什么工具，不执行
 *     - act()：拿到 AI 的决定，自己执行工具
 *
 *   所以用 DashScopeChatOptions.withInternalToolExecutionEnabled(false) 禁用自动执行。
 *   这样 AI 返回"我要调这些工具"后，Spring AI 直接把结果给我们，
 *   我们在 act() 里自己调 toolCallingManager.executeToolCalls() 执行。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用工具数组：来自 ToolRegistration.allTools() @Bean，包含6个工具
    private final ToolCallback[] availableTools;

    // think() 调完 AI 后，把 AI 的回复暂存到这里，act() 里用它来执行工具
    private ChatResponse toolCallChatResponse;

    // 工具执行管理器：实际调用 @Tool 注解方法、传参、收返回值
    private final ToolCallingManager toolCallingManager;

    /**
     * chatOptions：关键配置 —— 禁用 Spring AI 内置自动工具执行
     *
     * 作用：让 Spring AI 把"AI 要调哪些工具"的信息直接给我们，
     * 而不是 Spring AI 自己偷偷执行完再返回最终答案。
     * 这样我们才能在 act() 里拿到工具调用列表，手动执行，手动记录上下文。
     */
    private final ChatOptions chatOptions;

    /**
     * 构造方法：初始化工具相关组件
     *
     * @param availableTools 由 ToolRegistration.allTools() 提供的6个工具
     */
    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        // 保存工具数组，think() 里发给 AI，act() 里执行
        this.availableTools = availableTools;
        // 创建工具管理器（用默认配置即可）
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 自动工具执行，改为手动控制
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    /**
     * 思考阶段：向 AI 发消息，让 AI 决定调哪些工具
     *
     * 执行步骤：
     *   ① 追加 nextStepPrompt 到消息历史（提醒 AI 主动选工具）
     *   ② 构造 Prompt（消息历史 + chatOptions 禁用自动执行）
     *   ③ 调 ChatClient 发送请求，.tools(availableTools) 附带工具清单
     *   ④ 解析 AI 回复，获取 toolCallList（AI 要调的工具列表）
     *   ⑤ 有工具要调 → return true；没有 → 记录助手消息，return false
     *
     * @return true=需要调工具（执行 act），false=无需调工具
     */
    @Override
    public boolean think() {
        // ① 追加 nextStepPrompt：每轮都提醒 AI "选工具 + 完成后 terminate"
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }

        // ② 构造 Prompt：消息历史 + chatOptions（chatOptions 关掉了自动工具执行）
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);

        try {
            // ③ 调用 AI：
            //   .system(systemPrompt) → 系统提示词（AI 的岗位说明书）
            //   .tools(availableTools) → 把6个工具的 JSON Schema 发给 AI
            //   AI 回复包含：文字思考 + 要调用的工具清单（toolCallList）
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(availableTools)   // 已封装好的 ToolCallback[]，必须用 toolCallbacks() 而不是 tools()
                    .call()
                    .chatResponse();

            // 暂存 AI 回复，act() 里拿来执行工具
            this.toolCallChatResponse = chatResponse;

            // ④ 解析 AI 回复
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // toolCallList：AI 选择要调用的工具列表（可能为空表示不需要调工具）
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

            // 日志输出 AI 的思考文字（getText）和选择的工具
            log.info(getName() + "的思考：" + assistantMessage.getText());
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);

            // ⑤ 根据 toolCallList 是否为空决定是否需要执行 act()
            if (toolCallList.isEmpty()) {
                // AI 没有选工具：只需记录 AI 回复到消息历史，本步结束
                // 注意：调用工具时 Spring AI 会自动记录助手消息，但这里没调工具，要手动记录
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // AI 选了工具：不记录（act() 调工具时会自动记录），返回 true 让 act() 执行
                return true;
            }
        } catch (Exception e) {
            // 网络超时、API 限流等异常：记录错误消息到历史，让 Agent 能继续下一步
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            return false;
        }
    }

    /**
     * 行动阶段：执行 think() 里 AI 选好的工具
     *
     * 执行步骤：
     *   ① 检查是否有工具要调（防御性检查）
     *   ② toolCallingManager.executeToolCalls() 真正执行工具方法
     *   ③ 结果存入 conversationHistory（消息历史自动更新）
     *   ④ 检查是否调了 doTerminate 工具 → 设置状态 FINISHED
     *   ⑤ 返回工具执行结果字符串
     *
     * @return 工具执行结果描述
     */
    @Override
    public String act() {
        // ① 防御性检查：理论上不会发生（think 返回 true 才来这里），但保险
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        // ② 执行工具：
        //   toolCallingManager 找到工具方法（@Tool 注解） → 传入 AI 提供的参数 → 运行 → 收结果
        //   返回的 toolExecutionResult.conversationHistory() 包含：
        //     之前的全部消息 + AI 的工具调用消息 + 工具执行结果消息
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // ③ 用执行后的完整消息历史替换原来的 messageList（包含工具结果，AI 下轮能看到）
        setMessageList(toolExecutionResult.conversationHistory());

        // 取消息历史最后一条：就是工具执行结果消息（ToolResponseMessage）
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // ④ 检查是否调用了 terminate 工具（doTerminate）
        //   任意一个工具是 doTerminate → AI 认为任务完成，终止 Agent Loop
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            // 状态切换为 FINISHED，BaseAgent.run() 的循环条件 state != FINISHED 不满足，退出循环
            setState(AgentState.FINISHED);
        }

        // ⑤ 拼接所有工具的返回结果，记录到日志
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }
}

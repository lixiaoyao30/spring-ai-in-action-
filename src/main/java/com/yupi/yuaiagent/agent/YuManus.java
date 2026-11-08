package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * YuManus — AI 超级智能体（最终业务层，直接可用）
 *
 * ═══════════════════════════════════════════════════════════════
 * 【继承关系】
 * ═══════════════════════════════════════════════════════════════
 *
 *   BaseAgent        第1层：状态机 + 执行循环骨架（最稳定，最少改动）
 *       ↑
 *   ReActAgent       第2层：定义 think/act 抽象方法（ReAct 设计模式）
 *       ↑
 *   ToolCallAgent    第3层：实现 think/act，具体执行工具调用
 *       ↑
 *   YuManus（本类）  第4层：业务配置（提示词 + 工具 + 模型），最常改动
 *
 * ═══════════════════════════════════════════════════════════════
 * 【@Component 的作用 —— Spring 依赖注入入门】
 * ═══════════════════════════════════════════════════════════════
 *
 *   @Component 告诉 Spring："请帮我管理这个类，自动创建它放进容器（仓库）"
 *
 *   Spring 启动时自动完成：
 *     ① 扫描到 YuManus 有 @Component
 *     ② 看构造方法需要：ToolCallback[] + ChatModel
 *     ③ 去容器仓库找这两个对象：
 *          ToolCallback[]  → ToolRegistration.allTools() @Bean 提供
 *          ChatModel       → @Qualifier("openAiChatModel") 指定用 OpenAI 模型
 *     ④ 找到后自动 new YuManus(allTools, openAiChatModel)
 *     ⑤ 创建好的 YuManus 放进仓库，谁用谁取
 *
 *   你只需要写 @Autowired YuManus yuManus; 就能直接用，不需要手动 new
 *
 * ═══════════════════════════════════════════════════════════════
 * 【为什么用 openAiChatModel 而不是 dashscopeChatModel】
 * ═══════════════════════════════════════════════════════════════
 *
 *   容器里同时存在两个 ChatModel Bean：
 *     Bean① dashScopeChatModel  → 通义千问
 *     Bean② openAiChatModel     → OpenAI 兼容协议（配置在 application.yml）
 *
 *   YuManus 是超级智能体，工具调用能力要求高，使用 OpenAI 兼容模型。
 *   必须加 @Qualifier("openAiChatModel") 明确指定，
 *   否则 Spring 看到两个 ChatModel Bean 不知道注入哪个，启动报错。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【构造方法逐行说明】
 * ═══════════════════════════════════════════════════════════════
 *
 *   super(allTools)
 *     → 调父类 ToolCallAgent 构造方法，完成：
 *         this.availableTools = allTools          保存6个工具
 *         this.toolCallingManager = 新建工具管理器
 *         this.chatOptions = 禁用 Spring AI 自动执行工具（手动控制）
 *
 *   this.setName("yuManus")
 *     → 设置 Agent 名字，日志中显示 "yuManus的思考：..."
 *     → setName 是从 BaseAgent 通过 @Data 注解自动生成的 setter，继承而来
 *
 *   this.setSystemPrompt(SYSTEM_PROMPT)
 *     → 每次调用 AI 都会携带，告诉 AI "你是谁、你能干什么"
 *     → 类比：公司给新员工的岗位职责说明书，只发一次
 *
 *   this.setNextStepPrompt(NEXT_STEP_PROMPT)
 *     → 每次 think() 都追加到消息列表，提醒 AI 主动选工具并在完成后调 terminate
 *     → 类比：每次开会前念一遍会议规则，防止 AI "忘规矩"
 *
 *   this.setMaxSteps(20)
 *     → BaseAgent 默认是 10，YuManus 任务复杂改成 20
 *     → 防死循环保险丝：超过 20 步强制停止
 *
 *   ChatClient.builder(chatModel).defaultAdvisors(new MyLoggerAdvisor()).build()
 *     → 创建与 AI 通信的客户端（类比"电话机"）
 *     → chatModel 是"电话线路"（OpenAI 兼容协议）
 *     → MyLoggerAdvisor 是拦截器，每次请求/响应自动打日志
 *     → build() 固化所有配置，生成 ChatClient 对象
 */
@Component
public class YuManus extends ToolCallAgent {

    /**
     * Spring 自动调用此构造方法完成依赖注入。
     *
     * @param allTools        ToolRegistration @Bean 提供的 6 个工具数组
     * @param openAiChatModel OpenAI 兼容协议的 ChatModel，由 @Qualifier 精确指定
     */
    public YuManus(ToolCallback[] allTools,
                   @Qualifier("openAiChatModel") ChatModel openAiChatModel) {

        // 调父类构造：保存工具 + 创建工具管理器 + 禁用自动工具执行
        super(allTools);

        // Agent 名字：日志中用于区分不同 Agent 实例
        this.setName("yuManus");

        // 系统提示词：AI 的"岗位说明书"，每次对话自动携带
        String SYSTEM_PROMPT = """
                You are YuManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);

        // 下一步提示词：每次 think() 都追加，提醒 AI 选工具 + 完成后 terminate
        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                After using each tool, clearly explain the execution results and suggest the next steps.
                If you want to stop the interaction at any point, use the `terminate` tool/function call.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);

        // 最大步骤数：默认10，复杂任务改20，防止 AI 死循环
        this.setMaxSteps(20);

        // 创建 AI 对话客户端：openAiChatModel 是线路，MyLoggerAdvisor 是日志拦截器
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}

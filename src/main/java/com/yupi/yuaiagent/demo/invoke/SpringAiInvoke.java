package com.yupi.yuaiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 【接入方式三：Spring AI 框架调用】
 *
 * ─────────────────────────────────────────────────────────────
 * 三种方式对比：
 *
 *   SdkAiTextInvoke  → 直接用阿里 DashScope SDK，手动构建消息对象，约 20 行
 *   HttpAiInvoke     → 直接发 HTTP 请求，手动拼 JSON，约 25 行
 *   SpringAiInvoke   → 用 Spring AI 框架，3 行核心代码，换模型只改 yml
 *
 * Spring AI 最大的价值：
 *   换模型 = 只改 application.yml 的配置，这里的代码一行不动
 * ─────────────────────────────────────────────────────────────
 *
 * @Component 是什么意思？
 *   告诉 Spring："这个类由你来管理，帮我创建对象"
 *   Spring 会自动 new 出这个类的实例，并把它需要的依赖注入进来
 *
 * implements CommandLineRunner 是什么意思？
 *   CommandLineRunner 是 Spring Boot 提供的接口
 *   实现它的类，Spring Boot 启动完成后会自动调用 run() 方法
 *   相当于："项目一启动，就自动跑一次 run() 里的代码"
 *   常用于：启动时测试接口、初始化数据、演示 demo
 * ─────────────────────────────────────────────────────────────
 */
@Component
public class SpringAiInvoke implements CommandLineRunner {

    /**
     * ═══════════════════════════════════════════════════════════
     * 【@Autowired 和 @Resource 的区别】
     * ═══════════════════════════════════════════════════════════
     *
     *   @Autowired（Spring 原生）→ 先按【类型】找 Bean
     *   @Resource （Java 标准） → 先按【变量名】找 Bean
     *
     * 举个超简单的例子：
     *
     *   假设 Spring 容器里同时有两个 ChatModel Bean：
     *     Bean①  名字 = dashscopeChatModel，类型 = ChatModel（通义千问）
     *     Bean②  名字 = openAiChatModel，   类型 = ChatModel（OpenAI GPT）
     *
     *   用 @Autowired：
     *     @Autowired
     *     private ChatModel ???;   // ❌ 报错！两个都是 ChatModel 类型，不知道注入哪个
     *                              // 需要加 @Qualifier("dashscopeChatModel") 才能指定
     *
     *   用 @Resource：
     *     @Resource
     *     private ChatModel dashscopeChatModel;  // ✅ 按变量名找，精准命中 Bean①
     *
     *     @Resource
     *     private ChatModel openAiChatModel;     // ✅ 按变量名找，精准命中 Bean②
     *
     * ┌─────────────────────────────────────────────────────────┐
     * │  场景                       推荐                        │
     * │─────────────────────────────────────────────────────────│
     * │  只有一个同类型 Bean（最常见） @Autowired（写法更简洁）   │
     * │  同类型有多个 Bean           @Resource（按名字精准找）   │
     * └─────────────────────────────────────────────────────────┘
     *
     * ═══════════════════════════════════════════════════════════
     * 【dashscopeChatModel 这个名字必须这样写吗？】
     * ═══════════════════════════════════════════════════════════
     *
     * 必须和 Spring 容器里的 Bean 名字一致，不能随便改。
     *
     * Bean 名字从哪来的？
     *   spring-ai-alibaba-starter-dashscope 这个 starter 被引入后，
     *   SAA 框架内部自动创建了一个 Bean，名字固定叫 dashscopeChatModel。
     *   （就像你买了一台洗衣机，品牌给它起的型号你改不了）
     *
     * 所以 @Resource 这里的变量名 dashscopeChatModel：
     *   ✅ 和 SAA 自动创建的 Bean 名字完全一致 → 能找到，注入成功
     *   ❌ 如果改成 myModel、chatModel 等随意名字 → 按名找不到，
     *      退而按类型找，如果只有一个 ChatModel Bean 还是能成功，
     *      但如果有多个 Bean（比如同时配了 dashscope + openai）就会报错
     *
     * 小结：变量名 = SAA 帮你注册的 Bean 名，这是框架约定，不是你自己取的
     *
     * ═══════════════════════════════════════════════════════════
     * 【ChatModel 是什么？它的作用和逻辑是什么？】
     * ═══════════════════════════════════════════════════════════
     *
     * ChatModel 是 Spring AI 定义的【接口】，代表"一个能聊天的大模型"
     *
     * 你可以把它理解成"通用遥控器"：
     *   遥控器（ChatModel 接口）  → 统一的按钮：call(prompt)
     *   电视（DashScopeChatModel） → 通义千问的具体实现
     *   空调（OpenAiChatModel）    → OpenAI GPT 的具体实现
     *
     * 不管遥控哪台设备，你按的按钮（call 方法）永远一样。
     *
     * ─────────────────────────────────────────────────────────
     * 【Spring 根据 yml 配置决定用哪个模型，完整链路】
     * ─────────────────────────────────────────────────────────
     *
     *  ① pom.xml 引入 starter
     *       → Spring 自动创建对应 ChatModel Bean（Bean 名由框架固定）
     *
     *  ② application-local.yml 里的 api-key / model
     *       → 注入到对应 Bean 里（认证用 + 指定调哪个模型）
     *
     *  ③ @Resource 按变量名找到 Bean，注入进来
     *       → 调用 call() 时，实际走该 Bean 对应的大模型 API
     *
     * pom 引入 starter → yml 注入配置 → @Resource 按名找 Bean → 调用模型
     *
     * ─────────────────────────────────────────────────────────
     * 【同时使用两个模型：dashscope（通义千问）+ openai（GPT）】
     * ─────────────────────────────────────────────────────────
     *
     * application-local.yml 里已配置了两个模型：
     *   spring.ai.dashscope.api-key / model: qwen-plus   → 通义千问
     *   spring.ai.openai.api-key / model: gpt-5.6-sol    → OpenAI GPT
     *
     * 两个变量名分别对应各自的 Bean，@Resource 按名精准注入：
     *   dashscopeChatModel → spring-ai-alibaba-starter-dashscope 自动创建
     *   openAiChatModel    → spring-ai-openai-starter 自动创建
     *
     * 调用时想用哪个就用哪个，写法完全一样，只是变量名不同！
     * ═══════════════════════════════════════════════════════════
     */

    // ── 模型① 通义千问（qwen-plus）──────────────────────────────
    // @Qualifier 明确指定 Bean 名，防止多个 ChatModel Bean 时注入错误
    @Autowired
    @Qualifier("dashScopeChatModel")
    private ChatModel dashscopeChatModel;

    // ── 模型② OpenAI GPT（gpt-5.6-sol）─────────────────────────
    // Bean 名 openAiChatModel 由 spring-ai-starter-model-openai:1.1.2 固定注册
    // 版本必须用 1.1.2，与 SAA 依赖的 Spring AI 核心版本一致
    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel openAiChatModel;

    /**
     * Spring Boot 启动完成后自动执行此方法
     * 分别调用两个模型，对比它们的回复
     */
    @Override
    public void run(String... args) throws Exception {
        // ── 调用通义千问 ─────────────────────────────────────────
        try {
            AssistantMessage dashscopeReply = dashscopeChatModel
                    .call(new Prompt("你好，我是鱼皮，你是哪个模型？"))
                    .getResult()
                    .getOutput();
            System.out.println("=== 通义千问回复 ===");
            System.out.println(dashscopeReply.getText());
        } catch (Exception e) {
            System.out.println("=== 通义千问调用失败（跳过）: " + e.getMessage() + " ===");
        }

        // ── 调用 OpenAI GPT ──────────────────────────────────────
        try {
            AssistantMessage openAiReply = openAiChatModel
                    .call(new Prompt("你好，我是鱼皮，你是哪个模型？"))
                    .getResult()
                    .getOutput();
            System.out.println("=== OpenAI 回复 ===");
            System.out.println(openAiReply.getText());
        } catch (Exception e) {
            System.out.println("=== OpenAI 调用失败（跳过）: " + e.getMessage() + " ===");
        }
    }
}

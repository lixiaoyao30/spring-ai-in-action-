package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BaseAgent — 所有 Agent 的抽象基类（第1层）
 *
 * ═══════════════════════════════════════════════════════
 * 【这个类的职责】
 * ═══════════════════════════════════════════════════════
 *
 *   BaseAgent 是"骨架"，它定义了：
 *     1. Agent 有哪些字段（name、状态、步数、ChatClient、消息历史）
 *     2. 执行流程是什么（状态校验 → 循环调用 step() → 清理资源）
 *     3. 不决定 step() 怎么做 ← 这留给子类实现（抽象方法）
 *
 *   类比：项目经理（BaseAgent）定了流程规范，
 *         程序员（ReActAgent/ToolCallAgent）负责干具体活
 *
 * ═══════════════════════════════════════════════════════
 * 【设计模式：模板方法模式 (Template Method Pattern)】
 * ═══════════════════════════════════════════════════════
 *
 *   run() 方法是"模板"：定死了执行顺序
 *     ① 校验状态        ← BaseAgent 实现，不可改
 *     ② 循环调用 step() ← BaseAgent 调用，但 step() 由子类实现
 *     ③ 清理资源        ← BaseAgent 实现，子类可重写
 *
 *   abstract String step() 是"变化点"：
 *     子类（ReActAgent → ToolCallAgent）填入具体实现
 *
 *   好处：执行流程稳定、不重复，业务变化点清晰隔离
 *
 * ═══════════════════════════════════════════════════════
 * 【@Data 注解做了什么】
 * ═══════════════════════════════════════════════════════
 *
 *   Lombok @Data 等价于你手写了所有字段的：
 *     - getter：getName()、getState()、getChatClient()...
 *     - setter：setName()、setState()、setChatClient()...
 *     - toString()、equals()、hashCode()
 *
 *   这就是为什么子类 YuManus 的构造方法里可以直接调用
 *   this.setName("yuManus") ——  setter 是 @Data 自动生成的，
 *   子类继承后直接可用，不需要重新定义
 *
 * ═══════════════════════════════════════════════════════
 * 【@Slf4j 注解做了什么】
 * ═══════════════════════════════════════════════════════
 *
 *   Lombok @Slf4j 自动生成一行：
 *     private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);
 *
 *   这就是为什么代码里可以直接用 log.info() 而不需要声明 log 变量
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // Agent 的名字，日志中用于区分不同实例，如 "yuManus的思考："
    private String name;

    // 系统提示词：每次调用 AI 都会携带，告诉 AI "你是谁、你能干什么"
    private String systemPrompt;

    // 下一步提示词：每次 think() 时追加到消息列表，提醒 AI 主动选工具
    private String nextStepPrompt;

    // 状态机：初始为 IDLE，防止并发调用，详见 AgentState.java
    private AgentState state = AgentState.IDLE;

    // 当前执行到第几步（日志用，方便定位问题）
    private int currentStep = 0;

    // 最大步骤数：防死循环的"保险丝"，默认10，YuManus 改成了20
    private int maxSteps = 10;

    // AI 对话客户端：通过它向 AI 发送消息并获取回复
    private ChatClient chatClient;

    /**
     * 消息历史列表（对话上下文 / Memory）
     *
     * AI 的上下文窗口是有限的（如 qwen-max 约 32K token）。
     * 这个 messageList 就是发给 AI 的全部消息，包括：
     *   - UserMessage：用户/系统追加的提示
     *   - AssistantMessage：AI 上一轮的回复
     *   - ToolResponseMessage：工具执行的返回结果
     *
     * 消息越多，token 越多，响应越慢，费用越高。
     * maxSteps 限制了步骤数，间接控制了 messageList 的增长。
     */
    private List<Message> messageList = new ArrayList<>();

    /**
     * 同步执行 Agent（阻塞等待所有步骤完成后返回）
     *
     * 执行流程（模板方法模式）：
     *   ① 校验状态必须是 IDLE
     *   ② 校验 userPrompt 不能为空
     *   ③ 状态切换：IDLE → RUNNING
     *   ④ 把用户消息加入 messageList（历史上下文）
     *   ⑤ 循环调用 step()（最多 maxSteps 次）
     *   ⑥ 达到 maxSteps 或 state=FINISHED 时退出循环
     *   ⑦ finally 块里无论成败都执行 cleanup()
     *
     * @param userPrompt 用户输入的任务描述
     * @return 所有步骤结果拼接的字符串
     */
    public String run(String userPrompt) {
        // ① 状态校验：必须是 IDLE 才能启动，防止并发重入
        if (this.state != AgentState.IDLE) {
            throw new AgentException("Cannot run agent from state: " + this.state, this.state);
        }
        // ② 参数校验：空任务无意义，快速失败
        if (StrUtil.isBlank(userPrompt)) {
            throw new AgentException("Cannot run agent with empty user prompt");
        }

        // ③ 状态切换：IDLE → RUNNING，后续再调 run() 会在①处报错
        this.state = AgentState.RUNNING;

        // ④ 把用户消息加入历史，AI 后续能看到这条任务描述
        messageList.add(new UserMessage(userPrompt));

        List<String> results = new ArrayList<>();
        try {
            // ⑤ 主循环：最多执行 maxSteps 步，或 AI 调了 terminate 工具（state=FINISHED）提前结束
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);

                // 调用 step()：这是抽象方法，实际执行的是子类实现
                // 调用链：BaseAgent.run() → ReActAgent.step() → think() + act()
                // 每次 step() = "AI思考一轮 + 调用工具一轮"，可能耗时几秒到几十秒
                String stepResult = step();
                results.add("Step " + stepNumber + ": " + stepResult);
            }

            // ⑥ 超出步骤限制：强制标记 FINISHED，并记录原因
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);

        } catch (Exception e) {
            // 走到这里：step() 内部抛出了未被捕获的异常（工具崩溃、网络超时等）
            // 状态切为 ERROR，调用方 catch(AgentException) 可知道出错了
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();

        } finally {
            // ⑦ finally 块：无论成功/失败/异常，这里的代码都会执行
            // 用于重置状态，让 Agent 可以被下次复用
            // 类比：服务员不管这桌吃好吃坏，桌子都要收拾干净
            this.cleanup();
        }
    }

    /**
     * 流式执行 Agent（每完成一步立即推送结果给前端，不等全部结束）
     *
     * 与 run() 的区别：
     *   run()       → 所有步骤完成后一次性返回字符串（阻塞）
     *   runStream() → 每步完成立即通过 SSE 推送到浏览器（非阻塞）
     *
     * SSE（Server-Sent Events）：
     *   服务端单向推送技术，浏览器建立连接后保持打开，
     *   服务端随时可以 push 数据，无需轮询。
     *   类比：你订了外卖，外卖员每送到一个菜就喊一声，而不是等所有菜送完才通知。
     *
     * CompletableFuture.runAsync()：
     *   把 Agent 执行放到线程池里异步运行，主线程立即返回 SseEmitter 对象。
     *   浏览器拿到 SseEmitter 后建立 SSE 连接，异步线程每步执行完就 send() 推送。
     *
     * @param userPrompt 用户输入的任务描述
     * @return SseEmitter 对象（前端建立 SSE 连接用）
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建 SseEmitter，5分钟超时（Agent 执行可能很慢）
        SseEmitter sseEmitter = new SseEmitter(300000L);

        // 异步执行，避免阻塞 HTTP 主线程
        CompletableFuture.runAsync(() -> {
            // 基础校验（同 run()）
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
            }

            this.state = AgentState.RUNNING;
            messageList.add(new UserMessage(userPrompt));
            List<String> results = new ArrayList<>();
            try {
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);

                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);

                    // 每步完成立即推送到浏览器（这是 runStream 与 run 的核心区别）
                    sseEmitter.send(result);
                }
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                this.cleanup();
            }
        });

        // SSE 超时回调：5分钟内没有新消息，连接自动断开
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });

        // SSE 完成回调：正常结束时把状态切为 FINISHED
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        return sseEmitter;
    }

    /**
     * 单步执行（抽象方法，子类必须实现）
     *
     * 为什么定义为抽象方法？
     *   BaseAgent 不知道"一步"是什么：
     *     - 纯文本 Agent：一步 = 调一次 AI 生成回复
     *     - ToolCallAgent：一步 = 调 AI 思考 + 执行工具
     *   把变化点定义为抽象方法，子类各自填入具体逻辑。
     *
     * 调用链（多态）：
     *   run() 里写的是 step()（抽象）
     *   Java 运行时找到 YuManus 的实际类型
     *   沿继承链向上找，最终执行 ReActAgent.step()
     *   ReActAgent.step() 再调 think() 和 act()
     *
     * @return 本步骤的执行结果描述
     */
    public abstract String step();

    /**
     * 清理资源（模板钩子方法）
     *
     * BaseAgent 提供空实现（不做任何事）。
     * 子类可重写此方法来：重置字段、关闭连接、释放资源等。
     * 在 run() 和 runStream() 的 finally 块中自动调用，保证必定执行。
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}

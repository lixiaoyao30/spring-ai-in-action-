package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.agent.model.AgentState;

/**
 * AgentException — Agent 模块专用自定义异常
 *
 * ═══════════════════════════════════════════════════════
 * 【为什么不直接用 RuntimeException？】
 * ═══════════════════════════════════════════════════════
 *
 *   直接用 RuntimeException 有两个问题：
 *     1. catch(RuntimeException e) 会把所有运行时异常一网打尽，
 *        无法区分"是 Agent 的问题"还是"别处代码抛的"
 *     2. 没有结构化字段，日志里只能靠 message 字符串猜原因
 *
 *   自定义 AgentException 的好处：
 *     1. 精确 catch：catch(AgentException e) 只处理 Agent 相关错误
 *     2. 携带 agentState：知道异常发生时 Agent 处于哪个状态
 *     3. 语义清晰：一眼知道是 Agent 执行层面的问题
 *     4. 企业规范：大厂项目每个模块都有自己的异常体系
 *
 * ═══════════════════════════════════════════════════════
 * 【为什么继承 RuntimeException（非受检异常）？】
 * ═══════════════════════════════════════════════════════
 *
 *   Java 异常分两类：
 *
 *   RuntimeException（非受检异常）← AgentException 继承这个
 *     - 不需要在方法签名写 throws
 *     - 调用方不强制 try-catch
 *     - 适合"编程错误"：状态非法、参数为空
 *     - Spring @ControllerAdvice 可全局统一处理
 *
 *   Exception（受检异常）
 *     - 必须在方法签名写 throws，否则编译报错
 *     - 调用方必须 try-catch，否则编译报错
 *     - 适合"业务预期异常"：余额不足、商品下架
 *
 *   Agent 启动失败（状态非法、参数为空）属于编程错误，
 *   调用方不应该被迫写 try-catch，所以选 RuntimeException。
 *
 * ═══════════════════════════════════════════════════════
 * 【四个构造方法覆盖不同场景】
 * ═══════════════════════════════════════════════════════
 *
 *   // 场景1：只有错误信息
 *   throw new AgentException("userPrompt 不能为空");
 *
 *   // 场景2：带状态（推荐，最常用）
 *   throw new AgentException("非 IDLE 状态无法启动", AgentState.RUNNING);
 *
 *   // 场景3：包装其他异常（catch 后重新抛）
 *   throw new AgentException("工具调用失败", e);
 *   // 为什么要包装？保留原始异常堆栈（cause），同时升级为 AgentException 语义
 *
 *   // 场景4：完整版（带状态 + 原始异常）
 *   throw new AgentException("执行失败", AgentState.RUNNING, e);
 *
 * ═══════════════════════════════════════════════════════
 * 【调用方如何使用】
 * ═══════════════════════════════════════════════════════
 *
 *   try {
 *       yuManus.run("帮我查天气");
 *   } catch (AgentException e) {
 *       log.error("Agent 异常，状态={}，原因={}", e.getAgentState(), e.getMessage());
 *   }
 */
public class AgentException extends RuntimeException {

    /**
     * 异常发生时 Agent 所处的状态，便于排查：
     * 是启动时状态非法？还是执行中途崩溃？
     * 使用基础构造时为 null。
     */
    private final AgentState agentState;

    /** 只有错误信息（无状态上下文时使用） */
    public AgentException(String message) {
        super(message);
        this.agentState = null;
    }

    /** 带状态构造（推荐，最常用） */
    public AgentException(String message, AgentState agentState) {
        super(message);
        this.agentState = agentState;
    }

    /** 带原因构造：包装其他异常，保留原始堆栈 */
    public AgentException(String message, Throwable cause) {
        super(message, cause);
        this.agentState = null;
    }

    /** 完整构造：带状态 + 原始异常（信息最全，排查最方便） */
    public AgentException(String message, AgentState agentState, Throwable cause) {
        super(message, cause);
        this.agentState = agentState;
    }

    /** 获取异常发生时 Agent 的状态，可为 null */
    public AgentState getAgentState() {
        return agentState;
    }
}

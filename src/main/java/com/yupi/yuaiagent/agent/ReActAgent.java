package com.yupi.yuaiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * ReActAgent — ReAct 模式抽象类（第2层）
 *
 * ═══════════════════════════════════════════════════════
 * 【什么是 ReAct 模式？】
 * ═══════════════════════════════════════════════════════
 *
 *   ReAct = Reasoning（推理）+ Acting（行动）
 *   来自 2022 年 Google Brain 的论文：ReAct: Synergizing Reasoning and Acting in LLMs
 *
 *   传统 AI：只会回答问题，不会主动调工具
 *   ReAct AI：先推理（想用哪个工具？参数是什么？），再行动（真的去调工具）
 *
 *   一步 ReAct 循环：
 *     思考（think）→ 行动（act）→ 观察结果 → 思考 → 行动 → ...
 *
 *   例子：
 *     用户："帮我查今天上海天气"
 *     第1步 think：我需要调用 webSearch 工具，参数 "上海今日天气"
 *     第1步 act：调用 webSearch("上海今日天气") → 返回 "晴，25°C"
 *     第2步 think：有结果了，不需要再调工具，直接回答用户
 *     第2步 无 act（think 返回 false）
 *     结束（调用 terminate 工具或达到 maxSteps）
 *
 * ═══════════════════════════════════════════════════════
 * 【这个类的职责】
 * ═══════════════════════════════════════════════════════
 *
 *   1. 实现 BaseAgent.step()：固定"先 think，再 act"的执行顺序
 *   2. 声明 think() 和 act() 为抽象方法，子类 ToolCallAgent 填入具体实现
 *   3. step() 加了 try-catch，保证单步失败不会崩溃整个 Agent
 *
 *   继承关系：
 *     BaseAgent（第1层）← ReActAgent（第2层）← ToolCallAgent（第3层）← YuManus（第4层）
 *
 * ═══════════════════════════════════════════════════════
 * 【@EqualsAndHashCode(callSuper = true)】
 * ═══════════════════════════════════════════════════════
 *
 *   @Data 自动生成 equals/hashCode，但默认只比较本类字段。
 *   callSuper = true 让它同时也比较父类 BaseAgent 的字段。
 *   这样两个 Agent 对象比较时，name、state 等父类字段也参与比较。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    /**
     * 思考阶段（由子类 ToolCallAgent 实现）
     *
     * 职责：向 AI 发送当前消息历史 + nextStepPrompt，
     *       让 AI 决定调用哪些工具，返回"是否需要继续行动"。
     *
     * 返回 true：AI 选了工具，需要执行 act()
     * 返回 false：AI 认为任务完成，不需要调工具，step() 直接结束
     *
     * @return true=需要行动，false=无需行动（本步结束）
     */
    public abstract boolean think();

    /**
     * 行动阶段（由子类 ToolCallAgent 实现）
     *
     * 职责：把 think() 里 AI 选好的工具真正执行一遍，
     *       把工具结果加入 messageList，AI 下轮 think 时能看到结果。
     *
     * @return 工具执行结果的描述字符串
     */
    public abstract String act();

    /**
     * 单步执行：think + act（实现自 BaseAgent.step()）
     *
     * 流程：
     *   ① 调 think() → AI 推理，选工具，返回是否要行动
     *   ② 如果 think 返回 false → 无需行动，本步结束
     *   ③ 如果 think 返回 true  → 调 act()，执行工具
     *
     * try-catch 的作用：
     *   如果 think() 或 act() 内部抛异常，这里捕获后返回错误字符串，
     *   BaseAgent 的 run() 循环可以继续执行下一步，而不是整个 Agent 崩溃。
     *   （当然如果连续报错，建议 ToolCallAgent.think() 里也做了降级处理）
     *
     * @return 本步骤的执行结果描述
     */
    @Override
    public String step() {
        try {
            // ① 先思考：AI 决定调哪些工具，返回 true/false
            boolean shouldAct = think();
            if (!shouldAct) {
                // AI 说不需要调工具，本步完成
                return "思考完成 - 无需行动";
            }
            // ② 再行动：实际执行工具
            return act();
        } catch (Exception e) {
            log.error("步骤执行失败", e);
            return "步骤执行失败：" + e.getMessage();
        }
    }
}

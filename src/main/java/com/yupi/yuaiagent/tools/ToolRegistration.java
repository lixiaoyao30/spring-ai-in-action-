package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具注册类 —— 统一管理所有 Function Calling 工具
 *
 * ═══════════════════════════════════════════════════════════════
 * 【Function Calling / Tool Calling 是什么？】
 * ═══════════════════════════════════════════════════════════════
 *
 * "Function Calling" 这个术语实际上已经废弃，取而代之的是 "Tool"。
 * Tool 泛指一切 LLM 能够调用的外部工具，比 Function 更广义。
 * 目前 Tool 只有 Function Calling 这一种形式，两者可视为等价。
 *
 * 官方定义：
 *   LLM 根据用户的自然语言输入，自主决定调用哪些函数，并进行格式化函数调用的能力。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【Function Calling 标准四步流程】
 * ═══════════════════════════════════════════════════════════════
 *
 * Step 1：将用户的自然语言输入 + 已有工具的描述（ToolDefinition）作为输入传给 LLM
 *           → 大模型看到"工具说明书"（名称、描述、参数格式）
 *
 * Step 2：LLM 结合输入，决定调用哪些工具，并以格式化方式（JSON）输出调用意图
 *           → 例如：{ "tool": "generatePDF", "args": { "fileName": "plan.pdf", ... } }
 *           → 大模型只"说"要调什么，它自己不执行
 *
 * Step 3：用户端（这里是 Spring AI ToolCallingManager）接收 LLM 的格式化输出，
 *         对本地工具方法进行真正的调用，得到执行结果
 *           → 程序执行 generatePDF()，写入磁盘，返回文件路径
 *
 * Step 4：将工具执行结果传回给 LLM，使 LLM 有了所需的上下文信息，生成最终自然语言回复
 *           → 大模型："已为您生成 PDF，保存路径是 xxx"
 *
 * ═══════════════════════════════════════════════════════════════
 * 【本类的职责】
 * ═══════════════════════════════════════════════════════════════
 *
 * 将所有工具实例化后打包成 ToolCallback[]，注入到 Spring 容器。
 * LoveApp 通过 @Resource ToolCallback[] allTools 拿到全部工具，
 * 传给 chatClient.tools(allTools)，大模型按需自行选择调用（对应 Step 1/2）。
 * ToolCallingManager 负责 Step 3/4 的执行和回传，全程自动，无需手写。
 *
 * 设计模式：
 *   工厂模式   —— allTools() 集中创建并返回所有工具实例
 *   注册模式   —— 作为中央注册点，添加/移除工具只改这一个类
 *   适配器模式 —— ToolCallbacks.from() 把各工具类适配成统一的 ToolCallback 接口
 */
@Configuration
public class ToolRegistration {

    // 联网搜索 API Key，从 application.yml 读取
    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools() {
        return ToolCallbacks.from(
                new FileOperationTool(),
                new WebSearchTool(searchApiKey),
                new WebScrapingTool(),
                new ResourceDownloadTool(),
                new TerminalOperationTool(),
                new PDFGenerationTool()
        );
    }
}

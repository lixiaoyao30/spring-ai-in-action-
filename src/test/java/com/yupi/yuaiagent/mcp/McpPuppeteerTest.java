package com.yupi.yuaiagent.mcp;

import com.yupi.yuaiagent.app.LoveApp;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class McpPuppeteerTest {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel openAiChatModel;           // ① 证明用的是 OpenAI

    @Autowired
    private SyncMcpToolCallbackProvider toolCallbackProvider;  // ② MCP 工具提供者

    @Test
    void testPuppeteer() {
        // ① 打印模型类型 → 证明确实用的是 OpenAI
        System.out.println("【模型】" + openAiChatModel.getClass().getSimpleName());

        // ② 打印所有 MCP 工具列表 → 证明 puppeteer 工具已注册
        ToolCallback[] tools = toolCallbackProvider.getToolCallbacks();
        System.out.println("【MCP工具数量】" + tools.length);
        Arrays.stream(tools)
              .map(t -> "  - " + t.getToolDefinition().name())
              .forEach(System.out::println);

        boolean hasPuppeteer = Arrays.stream(tools)
              .anyMatch(t -> t.getToolDefinition().name().contains("puppeteer"));
        assertTrue(hasPuppeteer, "puppeteer 工具未注册");

        // ③ 让 AI 调用 puppeteer 截图 → 证明 MCP 工具被真实调用
        LoveApp loveApp = new LoveApp(openAiChatModel);
        loveApp.setToolCallbackProvider(toolCallbackProvider);

        String answer = loveApp.doChatWithMcp(
            "用 puppeteer 打开 https://www.baidu.com，截一张图，告诉我页面标题是什么",
            UUID.randomUUID().toString()
        );

        System.out.println("【AI回答】" + answer);
        assertNotNull(answer);
        assertTrue(answer.length() > 5);
    }
}

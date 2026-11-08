package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.app.LoveApp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * 全套工具集成测试 —— 验证所有工具能被大模型正确触发
 *
 * 涵盖工具：
 *   WebSearchTool        联网搜索（约会地点推荐）
 *   WebScrapingTool      网页抓取（抓取编程导航）
 *   ResourceDownloadTool 资源下载（下载图片）
 *   TerminalOperationTool 终端命令（执行 Python3 脚本）
 *   FileOperationTool    文件操作（保存恋爱档案）
 *   PDFGenerationTool    PDF 生成（七夕约会计划）
 */
@SpringBootTest
public class LoveAppAllToolsTest {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel openAiChatModel;

    @Autowired
    private ToolCallback[] allTools;

    // Spring AI 自动把 mcp-servers.json 里的 MCP 服务器工具封装成这个 Bean
    @Autowired
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    @Test
    void doChatWithTools() {
        LoveApp loveApp = new LoveApp(openAiChatModel);
        loveApp.setAllTools(allTools);

        // WebSearchTool：搜索约会地点
       // testMessage(loveApp, "周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");
        // WebScrapingTool：抓取编程导航网站
        testMessage(loveApp, "最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？如果有就说有 没有就说没有");
        // ResourceDownloadTool：下载图片
        testMessage(loveApp, "直接下载一张适合做手机壁纸的星空情侣图片为文件");
        // TerminalOperationTool：执行终端命令
        testMessage(loveApp, "执行 Python3 脚本来生成数据分析报告");
        // FileOperationTool：保存文件
        testMessage(loveApp, "保存我的恋爱档案为文件");
        // PDFGenerationTool：生成 PDF
        testMessage(loveApp, "生成一份'七夕约会计划'PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(LoveApp loveApp, String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = loveApp.doChatWithTools(message, chatId);
        System.out.println("问：" + message);
        System.out.println("答：" + answer);
        Assertions.assertNotNull(answer);
    }

    /**
     * MCP 测试：验证 filesystem MCP 服务器能正常读写文件
     *
     * mcp-servers.json 配置的是 @modelcontextprotocol/server-filesystem
     * 它提供两个工具（本质是 Function Calling，只是运行在外部 Node.js 进程里）：
     *   read_file  → 读取指定路径文件内容
     *   write_file → 写入内容到指定路径
     *
     * 允许操作的目录：attachment/（在 mcp-servers.json 里配置）
     * 文件保存位置：08-yu-ai-agent/attachment/love-note.txt
     *
     * 运行前提：本机已安装 Node.js（终端执行 node -v 能看到版本号）
     */
    @Test
    void doChatWithMcp() {
        LoveApp loveApp = new LoveApp(openAiChatModel);
        loveApp.setToolCallbackProvider(toolCallbackProvider);

        String chatId = UUID.randomUUID().toString();

        // 测试写文件：让 AI 通过 MCP 工具写入 attachment 目录
        String writeMsg = "帮我创建一个文件 spring-note.txt，内容是：spring里面有IOC和AOP。";
        String writeAnswer = loveApp.doChatWithMcp(writeMsg, chatId);
        System.out.println("问：" + writeMsg);
        System.out.println("答：" + writeAnswer);
        Assertions.assertNotNull(writeAnswer);

        // 测试读文件：让 AI 通过 MCP 工具读取刚写入的文件
        String readMsg = "读取 spring-note.txt 的内容，告诉我里面写了什么";
        String readAnswer = loveApp.doChatWithMcp(readMsg, chatId);
        System.out.println("问：" + readMsg);
        System.out.println("答：" + readAnswer);
        Assertions.assertNotNull(readAnswer);
    }
}

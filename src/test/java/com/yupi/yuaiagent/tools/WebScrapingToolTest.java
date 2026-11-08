package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.app.LoveApp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 网页抓取工具测试
 *
 * 涵盖工具：
 *   WebScrapingTool 网页抓取（根据 URL 获取页面内容）
 */
@SpringBootTest
class WebScrapingToolTest {

    // ── 单元测试：只测工具本身，大模型不参与 ──────────────────────────
    @Test
    void scrapeWebPage() {
        WebScrapingTool tool = new WebScrapingTool();
        String url = "https://www.codefather.cn";
        String result = tool.scrapeWebPage(url);
        System.out.println("抓取结果（前500字）：" + result.substring(0, Math.min(500, result.length())));
        assertNotNull(result);
    }

    // ── Function Calling 测试：大模型决定何时调 WebScrapingTool ──────
    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel openAiChatModel;

    @Autowired
    private ToolCallback[] allTools;

    @Test
    void testDoChatWithWebScraping() {
        LoveApp loveApp = new LoveApp(openAiChatModel);
        loveApp.setAllTools(allTools);

        String reply = loveApp.doChatWithTools(
                "帮我看看 https://www.codefather.cn 这个网站是干什么的，总结一下",
                "scraping-test"
        );
        System.out.println("大模型回复：" + reply);
        Assertions.assertNotNull(reply);
    }
}

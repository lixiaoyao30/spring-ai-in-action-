package com.yupi.yuaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

/**
 * 网页抓取工具
 *
 * 使用场景：用户提供一个 URL，AI 抓取页面内容后解读/摘要/提取信息
 * 例如："帮我看看这个页面写了什么：https://xxx"
 *
 * 与 WebSearchTool 的区别：
 *   WebSearchTool   → 输入关键词，返回多条搜索结果列表（不看具体页面）
 *   WebScrapingTool → 输入 URL，   返回该页面的完整 HTML 内容（精读单页）
 *
 * 典型组合用法（AI Agent 链式调用）：
 *   Step1 searchWeb("编程导航")  → 拿到 codefather.cn 的 URL
 *   Step2 scrapeWebPage(url)     → 抓取页面内容
 *   Step3 AI 总结页面内容 → 回答用户
 */
public class WebScrapingTool {

    /**
     * 抓取指定 URL 的网页 HTML 内容
     *
     * Jsoup.connect(url).get()：
     *   - 发送 HTTP GET 请求
     *   - 自动解析响应为 DOM 树（Document 对象）
     *   - document.html() 返回完整 HTML 字符串
     *
     * 注意：返回的是原始 HTML，包含大量标签
     * 实际生产中建议用 document.text() 只取纯文本，减少 token 消耗
     */
    @Tool(description = "Scrape the content of a web page. Use this when the user provides a URL and wants to know what the page contains.")
    public String scrapeWebPage(
            @ToolParam(description = "URL of the web page to scrape, e.g. https://www.codefather.cn") String url) {
        try {
            Document document = Jsoup.connect(url).get();
            // document.html()  → 完整 HTML（含标签，token 消耗大）
            // document.text()  → 纯文本（推荐生产环境使用，节省 token）
            return document.html();
        } catch (IOException e) {
            // return 不 throw：让大模型知道出错，由它生成友好提示
            return "Error scraping web page: " + e.getMessage();
        }
    }
}

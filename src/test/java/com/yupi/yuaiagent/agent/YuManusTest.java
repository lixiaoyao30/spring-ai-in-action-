package com.yupi.yuaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * YuManus 集成测试 —— 验证 Agent 端到端工具调用链路
 *
 * 测试策略：
 *   每个测试用例的 prompt 专门引导 AI 使用某一类工具，
 *   同时提示词中说明"若无 API key / 无法访问，请说明原因后直接生成模拟内容"，
 *   保证没有外部 API key 时测试也能走完完整流程、不报错退出。
 *
 * 涵盖工具：
 *   WebSearchTool        联网搜索（searchapi.io，需 SEARCH_API_KEY）
 *   WebScrapingTool      网页抓取（只需网络，无需 key）
 *   ResourceDownloadTool 资源下载（只需网络，无需 key）
 *   TerminalOperationTool 终端命令（本机环境，无需 key）
 *   FileOperationTool    文件读写（本地磁盘，无需 key）
 *   PDFGenerationTool    PDF 生成（本地，无需 key）
 *
 * 运行方式：
 *   IDEA：右键 → Run 'YuManusTest'
 *   Maven：mvn test -Dtest=YuManusTest -pl 08-yu-ai-agent
 *
 * 注意：YuManus 使用 openAiChatModel（OpenAI 兼容协议），
 *       需要 application-local.yml 配置好 OPENAI_API_KEY / OPENAI_BASE_URL
 */
@SpringBootTest
class YuManusTest {

    // Spring 自动注入 YuManus（@Component），无需手动 new
    @Resource
    private YuManus yuManus;

    // ─────────────────────────────────────────────────────────────
    // 综合测试：一个 prompt 触发多个工具串联使用
    // ─────────────────────────────────────────────────────────────

    /**
     * 综合任务：搜索 + 抓取 + 下载图片 + 生成 PDF
     *
     * 预期工具调用顺序：
     *   1. WebSearchTool      → 搜索上海约会地点
     *   2. WebScrapingTool    → 抓取某个推荐页面详情
     *   3. ResourceDownloadTool → 下载一张相关图片
     *   4. PDFGenerationTool  → 生成最终约会计划 PDF
     *
     * prompt 要求 AI：如果搜索工具无法使用（无 API key），
     * 请直接跳过搜索步骤，用自有知识完成后续任务。
     */
    @Test
    void testAllToolsInOnePipeline() {
        String userPrompt = """
                帮我完成一个约会计划任务，步骤如下：
                1. 搜索"上海情侣约会地点"（若搜索工具无法使用或无 API Key，请直接跳过此步骤，用自有知识代替）
                2. 尝试抓取网页 https://www.codefather.cn 并截取前 200 字内容
                3. 下载一张图片并保存到本地文件（使用这个链接：https://picsum.photos/400/300）
                4. 将以上内容整合，生成一份"上海两日约会计划.pdf"，包含地点推荐和注意事项
                完成每一步后告诉我结果，最后告知 PDF 保存路径。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== 综合测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        // 重置状态，让 Agent 可被下次复用（每个 @Test 独立）
        resetAgentState();
    }

    // ─────────────────────────────────────────────────────────────
    // 单工具测试：每个方法只聚焦一个工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 测试 WebSearchTool — 联网搜索
     *
     * 若 SEARCH_API_KEY 未配置（值为 not-configured），
     * searchWeb() 会返回 "Error searching Baidu: ..."，
     * AI 会看到这个错误信息，然后用自有知识继续回答，
     * 保证测试流程走完。
     */
    @Test
    void testWebSearch() {
        String userPrompt = """
                请使用搜索工具查询"2025年上海最浪漫的约会地点"。
                如果搜索工具返回错误或无法使用，请直接用你自有的知识回答，
                列出至少 3 个上海情侣约会地点推荐，并简要说明原因。
                完成后用 terminate 工具结束任务。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== WebSearch 测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        resetAgentState();
    }

    /**
     * 测试 WebScrapingTool — 网页抓取
     *
     * picsum.photos 是公开免费图片服务，无需 key，网络可达即可。
     */
    @Test
    void testWebScraping() {
        String userPrompt = """
                请抓取网页 https://www.codefather.cn 的内容，
                提取页面标题和前 300 字的文字摘要，告诉我这个网站主要是做什么的。
                完成后用 terminate 工具结束任务。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== WebScraping 测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        resetAgentState();
    }

    /**
     * 测试 ResourceDownloadTool — 资源下载
     *
     * picsum.photos 提供免费随机图片，无需 API key。
     */
    @Test
    void testResourceDownload() {
        String userPrompt = """
                请下载以下图片并保存为本地文件：
                URL：https://picsum.photos/seed/love/600/400
                保存文件名建议为 love-photo.jpg。
                下载完成后告诉我文件保存路径和文件大小。
                完成后用 terminate 工具结束任务。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== ResourceDownload 测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        resetAgentState();
    }

    /**
     * 测试 TerminalOperationTool — 终端命令执行
     *
     * 使用 echo 和 date 命令，完全本地执行，无需任何 key 或网络。
     */
    @Test
    void testTerminalOperation() {
        String userPrompt = """
                请执行以下终端命令并返回结果：
                1. 执行 echo "YuManus Terminal Test OK" 确认终端可用
                2. 执行 date 获取当前系统时间
                3. 执行 ls /tmp 列出临时目录文件
                告诉我每条命令的输出内容。
                完成后用 terminate 工具结束任务。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== Terminal 测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        resetAgentState();
    }

    /**
     * 测试 FileOperationTool — 文件读写
     *
     * 完全本地操作，无需 key 或网络。
     */
    @Test
    void testFileOperation() {
        String userPrompt = """
                请完成以下文件操作：
                1. 创建一个文件 /tmp/yu-manus-test.txt，写入内容："YuManus 文件操作测试成功，时间：2025年"
                2. 读取刚才创建的文件，把内容展示给我
                3. 告诉我文件路径
                完成后用 terminate 工具结束任务。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== FileOperation 测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        resetAgentState();
    }

    /**
     * 测试 PDFGenerationTool — PDF 生成
     *
     * 完全本地操作，无需 key 或网络。
     */
    @Test
    void testPDFGeneration() {
        String userPrompt = """
                请生成一份 PDF 文件，内容如下：
                文件名：恋爱备忘录.pdf
                内容：
                  标题：我的恋爱备忘录
                  第一条：每周至少一次约会
                  第二条：记住对方的重要纪念日
                  第三条：多表达感谢和欣赏
                  结语：爱需要用心经营
                生成完成后告诉我 PDF 保存的完整路径。
                完成后用 terminate 工具结束任务。
                """;
        String result = yuManus.run(userPrompt);
        System.out.println("=== PDFGeneration 测试结果 ===\n" + result);
        Assertions.assertNotNull(result);
        resetAgentState();
    }

    // ─────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────

    /**
     * 重置 Agent 状态为 IDLE，使每个 @Test 方法可以独立运行。
     *
     * 为什么需要重置？
     *   YuManus 是 Spring @Component（单例），所有测试方法共用同一个实例。
     *   run() 执行完后状态变成 FINISHED 或 ERROR，
     *   不重置的话下一个测试调 run() 会在状态校验处抛 AgentException。
     */
    private void resetAgentState() {
        yuManus.setState(com.yupi.yuaiagent.agent.model.AgentState.IDLE);
        yuManus.setCurrentStep(0);
        yuManus.getMessageList().clear();
    }
}

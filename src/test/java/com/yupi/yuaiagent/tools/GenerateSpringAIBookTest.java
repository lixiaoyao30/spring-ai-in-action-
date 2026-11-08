package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 生成 Spring AI 知识手册 PDF
 *
 * 涵盖工具：
 *   PDFGenerationTool PDF生成（将 Spring AI 完整知识体系写入 PDF 文件）
 */
@SpringBootTest
public class GenerateSpringAIBookTest {

    @Test
    public void generateSpringAIBook() {
        PDFGenerationTool tool = new PDFGenerationTool();

        String content =
            "================================================================\n" +
            "Spring AI 与 Spring AI Alibaba 完整知识手册\n" +
            "结合 yu-ai-agent 鱼皮恋爱顾问项目实战\n" +
            "================================================================\n\n" +

            "【第一章】技术栈与版本说明\n" +
            "================================================================\n\n" +
            "版本对应关系：\n" +
            "  Spring AI Alibaba（SAA）：1.1.2.0\n" +
            "  Spring AI 核心：1.1.2（由 SAA 传递依赖自动引入）\n" +
            "  Spring Boot：3.5.8（SAA 1.1.2.0 要求 3.5.x）\n" +
            "  Java：21\n\n" +
            "为什么用 SAA 不直接用 Spring AI：\n" +
            "  Spring AI（官方）只支持 OpenAI/Claude 等国际模型，需要翻墙。\n" +
            "  SAA = Spring AI + 阿里云适配，额外支持通义千问全系列，\n" +
            "  spring.ai.dashscope.api-key 一行配置即可，有中文文档。\n" +
            "  结论：SAA 是 Spring AI 的超集，用 SAA = Spring AI 全部功能 + 国内模型开箱即用。\n\n" +
            "API 版本变化（避免被旧教程坑）：\n" +
            "  Spring AI 1.0.x（旧教程）：Advisor 请求类 AdvisedRequest，响应类 AdvisedResponse\n" +
            "  Spring AI 1.1.x（当前版本）：Advisor 请求类 ChatClientRequest，响应类 ChatClientResponse\n\n" +

            "【第二章】ChatModel vs ChatClient（面试必考）\n" +
            "================================================================\n\n" +
            "ChatModel：底层 AI 模型接口，只负责发请求、收响应。\n" +
            "  用法：dashscopeChatModel.call(new Prompt(\"你好\")).getResult().getOutput().getText()\n" +
            "  特点：轻量、直接，适合简单调用和单元测试。\n\n" +
            "ChatClient：高层封装，在 ChatModel 基础上增加了：\n" +
            "  - defaultSystem：全局系统提示词，每次请求自动携带\n" +
            "  - defaultAdvisors：拦截器链（记忆、安全过滤、日志）\n" +
            "  - 链式 API：.prompt().user(msg).call().content()\n" +
            "  特点：适合生产环境，功能完整。\n\n" +
            "本项目 LoveApp 使用 ChatClient，封装对话逻辑：\n" +
            "  chatClient = ChatClient.builder(model)\n" +
            "      .defaultSystem(systemPrompt)\n" +
            "      .defaultAdvisors(\n" +
            "          MessageChatMemoryAdvisor.builder(chatMemory).build(),\n" +
            "          SafeGuardAdvisor.builder()...build(),\n" +
            "          new MyLoggerAdvisor()\n" +
            "      ).build();\n\n" +

            "【第三章】Advisor 机制——洋葱模型（核心必读）\n" +
            "================================================================\n\n" +
            "Advisor 是什么：请求发给 AI 前先经过它（before），AI 回复后再经过它（after）。\n" +
            "可以挂多个，按 order 顺序执行。数字越小越先执行。\n\n" +
            "执行顺序——洋葱模型：\n" +
            "  BEFORE 阶段（order 从小到大，正序）：\n" +
            "    1. MessageChatMemoryAdvisor.before() → 读历史记录，注入 Prompt\n" +
            "    2. SafeGuardAdvisor.before() → 检查敏感词，命中直接返回，不调 AI\n" +
            "    3. MyLoggerAdvisor.before() → 打印请求日志\n" +
            "  调用 AI（HTTP 请求，耗时最长）\n" +
            "  AFTER 阶段（order 从大到小，逆序）：\n" +
            "    3. MyLoggerAdvisor.after() → 打印 token 用量和 AI 回复\n" +
            "    2. SafeGuardAdvisor.after() → 无操作\n" +
            "    1. MessageChatMemoryAdvisor.after() → 保存本轮对话到记忆库\n\n" +
            "重要：你只调 .call()，框架自动触发整条链。你永远不手动调 before()/after()。\n\n" +
            "自定义 Advisor 三步走：\n" +
            "  Step1：implements BaseAdvisor\n" +
            "  Step2：重写 before() 和 after()（两个方法都必须 return，否则链断）\n" +
            "  Step3：注册到 defaultAdvisors\n\n" +
            "企业最佳实践：单一职责，一个 Advisor 只做一件事。\n" +
            "  MyLoggerAdvisor → 只打日志和统计 token\n" +
            "  SafeGuardAdvisor → 只过滤敏感词\n\n" +

            "【第四章】对话记忆——四种存储方式\n" +
            "================================================================\n\n" +
            "两层设计：\n" +
            "  MessageWindowChatMemory（策略层）：决定保留几条（maxMessages=20）\n" +
            "      └── ChatMemoryRepository（存储层）：决定存哪里\n\n" +
            "四种存储实现：\n" +
            "  1. InMemoryChatMemoryRepository：存 JVM 内存，重启丢失，开发测试用\n" +
            "  2. RedisChatMemoryRepository（自定义）：存 Redis，7天过期，生产推荐\n" +
            "     Redis 命令：RPUSH/LRANGE/EXPIRE/DEL/SADD\n" +
            "  3. MysqlChatMemoryRepository（自定义）：存 MySQL，SQL 可查，审计分析用\n" +
            "     操作：SELECT WHERE conversation_id=? / DELETE + INSERT\n" +
            "  4. FileBasedChatMemoryRepository（自定义）：存本地 JSON 文件，零依赖，离线调试\n\n" +
            "LoveApp 四个构造方法对应四种存储：\n" +
            "  new LoveApp(chatModel)              → 内存版\n" +
            "  new LoveApp(chatModel, redis)       → Redis 版\n" +
            "  new LoveApp(chatModel, jdbc)        → MySQL 版\n" +
            "  new LoveApp(chatModel, fileDir)     → 文件版\n\n" +
            "chatId 的作用：每个用户独立一份记忆，互不干扰。\n" +
            "  不传 chatId → 所有用户共享记忆 → 数据混乱\n" +
            "  传 chatId → 每个用户隔离 → 正确\n\n" +

            "【第五章】RAG 知识库完全指南\n" +
            "================================================================\n\n" +
            "RAG 是什么（Retrieval-Augmented Generation，检索增强生成）：\n" +
            "  不把整本书喂给 AI，而是：\n" +
            "    1. 把知识库切片向量化，存入向量数据库\n" +
            "    2. 用户提问时，先检索最相关的几片\n" +
            "    3. 把检索结果拼进 Prompt，AI 基于这些片段回答\n" +
            "  优点：AI 只看相关内容，节省 token，准确率更高。\n\n" +
            "核心流程：\n" +
            "  离线（一次）：文档 → 切片 → Embedding → 向量 → 存入 VectorStore\n" +
            "  在线（每次）：用户问题 → Embedding → 向量 → 相似搜索 → 取 TopK 文档\n" +
            "               → 拼入 Prompt → AI 基于文档回答\n\n" +
            "本项目三种 RAG 实现：\n\n" +
            "【本地 RAG】SimpleVectorStore（内存向量库）\n" +
            "  存储：JVM 内存，程序启动时向量化 documents/*.md（15条文档）\n" +
            "  向量化：本地调阿里云 Embedding API\n" +
            "  配置类：LocalMemoryVectorStoreConfig\n" +
            "  方法：LoveApp.doChatWithLocalRag(message, chatId, status)\n" +
            "  支持 filterExpression：按文档 metadata.status 过滤（单身/恋爱/已婚）\n\n" +
            "【云端 RAG】阿里云百炼 DashScope\n" +
            "  存储：阿里云百炼控制台，云端完成向量化+搜索\n" +
            "  特点：不用自己管数据库，知识库在控制台上传维护\n" +
            "  Advisor：DashScopeDocumentRetriever\n" +
            "  方法：LoveApp.doChatWithDashScopeRag(message, chatId)\n\n" +
            "【pgVector RAG】Supabase PostgreSQL\n" +
            "  存储：Supabase 云端 PostgreSQL + pgvector 扩展\n" +
            "  特点：自己掌控数据，适合数据隐私要求高的场景\n" +
            "  方法：LoveApp.doChatWithSupobasePgVectorRag(message, chatId)\n\n" +
            "RAG 优化技术：\n" +
            "  Pre-Retrieval（检索前）：查询重写（QueryRewriter）、多查询扩展（MultiQueryExpander）\n" +
            "  Retrieval（检索中）：topK=3, similarityThreshold=0.5\n" +
            "  Post-Retrieval（检索后）：文档重排，去除低质量文档\n\n" +

            "【第六章】pgVector + Supabase 双数据源隔离原理\n" +
            "================================================================\n\n" +
            "问题：项目同时有 MySQL（对话记忆）和 PostgreSQL（向量库）两个数据库。\n" +
            "Spring Boot 默认只有一个 dataSource Bean（MySQL），\n" +
            "如果让 pgVector 自动配置找 dataSource，它会找到 MySQL 那个，\n" +
            "然后对 MySQL 执行 PostgreSQL 专属命令（CREATE EXTENSION vector）→ 报错！\n\n" +
            "解决方案：SupabasePgVectorStoreConfig 手动建专属 PostgreSQL 连接池\n" +
            "  @Bean(name = \"pgDataSource\")\n" +
            "  public DataSource pgDataSource() {\n" +
            "      HikariDataSource ds = new HikariDataSource();\n" +
            "      ds.setJdbcUrl(pgJdbcUrl); // Supabase 连接串\n" +
            "      ds.setDriverClassName(\"org.postgresql.Driver\");\n" +
            "      return ds;\n" +
            "  }\n\n" +
            "结果：容器里同时存在两个独立连接池：\n" +
            "  dataSource   → MySQL   ← Spring 自动建，MysqlChatMemoryRepository 用这个\n" +
            "  pgDataSource → PostgreSQL ← 手动建，pgVectorStore 用这个\n\n" +
            "@ConditionalOnMissingBean(PgVectorStore.class)：\n" +
            "  Spring AI 自动配置的保护机制——容器里已有 PgVectorStore Bean 时，自动配置跳过，\n" +
            "  不会再建一个用 MySQL 的，避免冲突。\n\n" +
            "Supabase 配置（application-local.yml，不提交 Git）：\n" +
            "  spring.ai.vectorstore.pgvector.jdbc-url: jdbc:postgresql://...\n" +
            "  spring.ai.vectorstore.pgvector.jdbc-username: postgres.xxx\n" +
            "  spring.ai.vectorstore.pgvector.jdbc-password: xxx\n\n" +

            "【第七章】Function Calling 完全指南\n" +
            "================================================================\n\n" +
            "Function Calling 是什么：\n" +
            "  让 AI 不只是回答文字，还能调用你写好的 Java 方法，操作真实世界。\n" +
            "  AI 自己判断需不需要用工具、用哪个工具、传什么参数。\n\n" +
            "与 RAG 的区别：\n" +
            "  RAG：给 AI 提供知识（文档检索，增强回答质量）\n" +
            "  Function Calling：给 AI 提供工具（执行操作，产生真实效果）\n" +
            "  两者可以同时用。\n\n" +
            "核心注解：\n" +
            "  @Tool(description = \"工具描述\")：标记这个方法是可供 AI 调用的工具。\n" +
            "    description 是关键！AI 读 description 来判断要不要调这个工具。\n" +
            "  @ToolParam(description = \"参数描述\")：描述参数含义，AI 从用户问题中提取参数值。\n\n" +
            "完整执行流程（6步）：\n" +
            "  Step1：程序把工具清单（名字+description+参数）发给大模型\n" +
            "  Step2：用户提问，大模型分析需不需要工具\n" +
            "  Step3：需要 → 大模型返回工具调用意图（JSON，不是答案！）\n" +
            "  Step4：Spring AI 框架拦截意图，自动调用对应 Java 方法\n" +
            "  Step5：把工具执行结果塞回给大模型\n" +
            "  Step6：大模型基于结果生成最终回答\n" +
            "  不需要工具 → 直接回答，和普通对话一样\n\n" +
            "关键点：大模型自己不执行 Java 代码，Spring AI 框架才是真正执行者。\n" +
            "安全设计：所有操作都在你自己程序里受控执行。\n\n" +
            "LoveApp.doChatWithTools 代码：\n" +
            "  public String doChatWithTools(String message, String chatId) {\n" +
            "      ChatResponse chatResponse = chatClient\n" +
            "          .prompt()\n" +
            "          .user(message)\n" +
            "          .tools(allTools)  // 注册全套工具箱\n" +
            "          .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))\n" +
            "          .call()\n" +
            "          .chatResponse();\n" +
            "      return chatResponse.getResult().getOutput().getText();\n" +
            "  }\n\n" +

            "【第八章】六大工具实现详解\n" +
            "================================================================\n\n" +
            "文件保存根目录（FileConstant.FILE_SAVE_DIR）：\n" +
            "  System.getProperty(\"user.dir\") + \"/tmp\"\n" +
            "  建议加入 .gitignore，避免提交隐私信息。\n\n" +
            "工具1：FileOperationTool（文件读写）\n" +
            "  readFile(fileName)：读取 tmp/file/ 目录下的文件内容\n" +
            "  writeFile(fileName, content)：写入内容到 tmp/file/ 目录\n" +
            "  使用 Java NIO Files API，自动创建目录。\n" +
            "  触发场景：用户说\"保存我的恋爱档案\"、\"读取之前保存的文件\"\n\n" +
            "工具2：WebSearchTool（联网搜索）\n" +
            "  searchWeb(query)：调用 SearchAPI 搜索百度，返回前5条结果\n" +
            "  需要在 application.yml 配置 search-api.api-key\n" +
            "  返回：标题+链接+摘要的 JSON\n" +
            "  触发场景：用户说\"搜一下\"、\"查一下最新信息\"\n\n" +
            "工具3：WebScrapingTool（网页抓取）\n" +
            "  scrapeWebPage(url)：用 jsoup 抓取指定 URL 的网页内容\n" +
            "  返回完整 HTML（生产建议用 document.text() 只取纯文本，省 token）\n" +
            "  触发场景：用户提供 URL，说\"看看这个网站是干什么的\"\n\n" +
            "工具4：ResourceDownloadTool（资源下载）\n" +
            "  downloadResource(url, fileName)：用 Hutool HttpUtil 下载文件到 tmp/download/\n" +
            "  支持图片、文档等任意文件类型\n" +
            "  触发场景：用户说\"下载一张图片\"、\"保存这个文件\"\n\n" +
            "工具5：TerminalOperationTool（终端命令）\n" +
            "  executeTerminalCommand(command)：执行系统命令，返回输出结果\n" +
            "  macOS/Linux 用 Runtime.getRuntime().exec()\n" +
            "  Windows 需改为 ProcessBuilder(\"cmd.exe\", \"/c\", command)\n" +
            "  触发场景：用户说\"执行 Python 脚本\"、\"运行命令\"\n" +
            "  注意：此工具有安全风险，生产环境需严格校验输入。\n\n" +
            "工具6：PDFGenerationTool（PDF 生成）\n" +
            "  generatePDF(fileName, content)：用 itext 9.1.0 生成 PDF 文件\n" +
            "  中文字体：PdfFontFactory.createFont(\"STSongStd-Light\", \"UniGB-UCS2-H\")\n" +
            "            itext 内置宋体，支持中文，无需额外字体文件\n" +
            "  文件保存到：tmp/pdf/ 目录\n" +
            "  触发场景：用户说\"生成一份 PDF\"、\"导出文档\"\n\n" +

            "【第九章】ToolRegistration 统一注册模式\n" +
            "================================================================\n\n" +
            "工具注册类的作用：统一管理所有工具，一次注册，到处使用。\n\n" +
            "代码：\n" +
            "  @Configuration\n" +
            "  public class ToolRegistration {\n" +
            "      @Value(\"${search-api.api-key}\")\n" +
            "      private String searchApiKey;\n\n" +
            "      @Bean\n" +
            "      public ToolCallback[] allTools() {\n" +
            "          return ToolCallbacks.from(\n" +
            "              new FileOperationTool(),\n" +
            "              new WebSearchTool(searchApiKey),\n" +
            "              new WebScrapingTool(),\n" +
            "              new ResourceDownloadTool(),\n" +
            "              new TerminalOperationTool(),\n" +
            "              new PDFGenerationTool()\n" +
            "          );\n" +
            "      }\n" +
            "  }\n\n" +
            "暗含的设计模式：\n" +
            "  工厂模式：allTools() 集中创建并返回所有工具实例\n" +
            "  注册模式：中央注册点，添加/移除工具只改这一个类\n" +
            "  适配器模式：ToolCallbacks.from() 把各工具类适配成统一的 ToolCallback 接口\n" +
            "  依赖注入模式：@Value 注入 API Key，@Bean 让 Spring 管理\n\n" +
            "使用方式：\n" +
            "  测试类中：@Autowired ToolCallback[] allTools;\n" +
            "            loveApp.setAllTools(allTools);\n" +
            "  LoveApp 中：通过 setter 注入（因为 LoveApp 是手动 new 的，Spring 不管理）\n\n" +

            "【第十章】PromptTemplate 提示词模板\n" +
            "================================================================\n\n" +
            "作用：把提示词从代码里分离，支持变量占位符，改提示词不用改代码。\n\n" +
            "本项目模板文件：src/main/resources/template/love-system.st\n" +
            "内容示例：\n" +
            "  扮演深耕恋爱心理领域的专家，拥有 {years} 年情感咨询经验。\n" +
            "  回复风格：{style}。每次回复控制在 {max_words} 字以内。\n\n" +
            "使用：\n" +
            "  SystemPromptTemplate template = new SystemPromptTemplate(\n" +
            "      new ClassPathResource(\"template/love-system.st\"));\n" +
            "  Message msg = template.createMessage(Map.of(\n" +
            "      \"years\", \"8\", \"style\", \"温柔耐心\", \"max_words\", \"300\"));\n\n" +
            "优点：产品/运营直接改文件调整 AI 人设，不需要改代码重新发版。\n\n" +

            "【第十一章】结构化输出（Structured Output）\n" +
            "================================================================\n\n" +
            "问题：AI 默认返回纯文字，程序无法直接用。\n" +
            "解决：结构化输出让 AI 直接返回 Java 对象。\n\n" +
            "工作原理（两步自动完成）：\n" +
            "  Step1（调用前）：FormatProvider 自动在 Prompt 末尾追加格式指令\n" +
            "    \"Your response should be in JSON format. Match this Java class: LoveReport{score,advice,nextStep}\"\n" +
            "  Step2（调用后）：Converter 把 AI 返回的 JSON 文本解析成 Java 对象\n\n" +
            "使用：\n" +
            "  record LoveReport(Integer score, String advice, String nextStep) {}\n\n" +
            "  LoveReport report = chatClient\n" +
            "      .prompt().user(message).call()\n" +
            "      .entity(LoveReport.class); // 一行：追加格式指令 + 自动解析\n\n" +
            "  int score = report.score(); // 直接用，不需要解析字符串\n\n" +
            "注意：AI 不保证 100% 返回合法 JSON，生产环境加 try-catch 兜底。\n\n" +

            "【第十二章】面试高频题汇总\n" +
            "================================================================\n\n" +
            "Q1：Spring AI 的 Advisor 是什么？before/after 怎么工作？\n" +
            "A：Advisor 是拦截器，before() 在发给 AI 前执行，after() 在 AI 回复后执行。\n" +
            "   按 order 正序执行 before，逆序执行 after（洋葱模型）。\n" +
            "   通过 .call() 自动触发，开发者不手动调 before()/after()。\n\n" +
            "Q2：RAG 和 Function Calling 的区别？\n" +
            "A：RAG 给 AI 提供知识（检索文档，增强回答质量）；\n" +
            "   Function Calling 给 AI 提供工具（执行操作，产生真实效果）。\n" +
            "   两者可以同时使用。\n\n" +
            "Q3：项目有两个 DataSource（MySQL + PostgreSQL）如何处理？\n" +
            "A：手动建 pgDataSource Bean 专用于 PostgreSQL，与默认 dataSource（MySQL）隔离。\n" +
            "   利用 @ConditionalOnMissingBean(PgVectorStore.class) 阻止自动配置抢占 MySQL 数据源。\n\n" +
            "Q4：@Autowired vs @Resource 区别？\n" +
            "A：@Autowired 按类型注入，多个同类型 Bean 时配合 @Qualifier 指定名字。\n" +
            "   @Resource 先按名字注入，找不到再按类型。\n" +
            "   有多个同类型 Bean 时推荐 @Autowired + @Qualifier，语义更明确。\n\n" +
            "Q5：ChatModel vs ChatClient 区别？\n" +
            "A：ChatModel 是底层接口，只发请求收响应。\n" +
            "   ChatClient 是高层封装，增加了系统提示词、Advisor 链、链式 API。\n" +
            "   生产环境用 ChatClient，测试或简单场景用 ChatModel。\n\n" +
            "Q6：对话记忆有哪几种存储方式，如何选择？\n" +
            "A：内存版（InMemory）：开发测试；Redis 版：生产推荐，快且支持过期；\n" +
            "   MySQL 版：需要 SQL 查询和审计；文件版：零依赖，离线调试。\n" +
            "   本质都是换 ChatMemoryRepository 实现，Advisor 调用时机不变。\n\n" +
            "Q7：Function Calling 工具怎么写？AI 怎么知道用哪个工具？\n" +
            "A：工具类普通 Java 类，方法加 @Tool(description) 和 @ToolParam(description)。\n" +
            "   AI 读取每个工具的 description，根据用户问题语义判断调哪个。\n" +
            "   description 写得越清晰，AI 选择越准确。\n\n" +
            "Q8：如何防止 AI 返回有害内容？\n" +
            "A：使用内置 SafeGuardAdvisor，配置敏感词列表。\n" +
            "   命中敏感词时直接返回友好提示，AI 不调用（省钱+安全）。\n" +
            "   生产环境敏感词列表从数据库加载，不硬编码。\n\n" +

            "================================================================\n" +
            "本手册涵盖 yu-ai-agent 项目全部核心知识点\n" +
            "项目地址：08-yu-ai-agent 模块\n" +
            "Spring AI Alibaba 版本：1.1.2.0 / Spring Boot：3.5.8 / Java：21\n" +
            "================================================================\n";

        String result = tool.generatePDF("Spring_AI_知识手册.pdf", content);
        System.out.println("生成结果：" + result);
        assertNotNull(result);
    }
}

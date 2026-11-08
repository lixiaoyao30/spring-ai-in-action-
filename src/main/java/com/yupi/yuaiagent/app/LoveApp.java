package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.advisor.ReReadingAdvisor;
import com.yupi.yuaiagent.memory.FileBasedChatMemoryRepository;
import com.yupi.yuaiagent.memory.MysqlChatMemoryRepository;
import com.yupi.yuaiagent.memory.RedisChatMemoryRepository;
import com.yupi.yuaiagent.rag.query.LoveAppMultiQueryExpander;
import com.yupi.yuaiagent.rag.query.LoveAppRagCustomAdvisorFactory;
import com.yupi.yuaiagent.rag.query.QueryRewriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * LoveApp — 恋爱顾问 AI 应用  完整知识地图
 * ═══════════════════════════════════════════════════════════════════

 *   一张图看清全局
 *
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  一、4 个对外方法                                                │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  doChat                普通对话，凭 AI 自身训练数据回答            │
 * │  doChatWithReport      结构化输出，返回 LoveReport 对象            │
 * │  doChatWithLocalRag    本地 RAG，基于内存 SimpleVectorStore        │
 * │  doChatWithDashScopeRag 云端 RAG，知识库托管在阿里云百炼控制台     │
 * │  doChatWithPgVectorRag  云端 RAG，知识库存在 Supabase pgVector     │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  二、4 种存储方式（构造方法决定，传哪个用哪个）                   │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  ① new LoveApp(chatModel)              内存  重启即丢 开发演示   │
 * │  ② new LoveApp(chatModel, redis)       Redis 重启不丢 生产推荐   │
 * │  ③ new LoveApp(chatModel, jdbc)        MySQL SQL可查  审计分析   │
 * │  ④ new LoveApp(chatModel, fileDir)     文件  零依赖   离线调试   │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  三、buildChatClient() — 构造时装配"流水线"（只执行一次）         │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  步骤1：创建 chatMemory（记事本）                                │
 * │    MessageWindowChatMemory.maxMessages(20)                       │
 * │      → 超过20条自动丢弃最旧的                                    │
 * │      → 自己不存数据，委托 Repository 实际读写                    │
 * │                                                                  │
 * │  步骤2：ChatClient.builder(model)                                │
 * │    .defaultSystem(系统提示词)   AI 角色设定，每次请求自动带上    │
 * │    .defaultAdvisors(            注册3个拦截器，排好队等待触发     │
 * │        ① MessageChatMemoryAdvisor(chatMemory)                    │
 * │           持有 chatMemory，知道在正确时机读写历史                 │
 * │        ② SafeGuardAdvisor                                        │
 * │           敏感词过滤，命中则拦截，不调 AI                        │
 * │        ③ MyLoggerAdvisor                                         │
 * │           打印请求日志和 token 用量                              │
 * │    ).build()  →  chatClient 字段赋值完毕，等待 .call() 触发      │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  四、.call() 洋葱模型 — 每次 doChat 触发                        │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  .call() 之前全是"准备阶段"，不发任何网络请求：                  │
 * │    .prompt()          → 创建本次请求对象（空白表单）             │
 * │    .user(message)     → 填入用户消息                            │
 * │    .advisors(xxx)     → 追加本次额外的拦截器，插入队列最内层     │
 * │    .advisors(spec->)  → 传运行时参数（如 chatId），不加新拦截器  │
 * │                                                                  │
 * │  两种注册 Advisor 的方式对比：                                   │
 * │    buildChatClient() 里 .defaultAdvisors(①②③)                  │
 * │      → 全局固定，每次 .call() 都有，顺序①②③                    │
 * │    .call() 前 .advisors(④)                                      │
 * │      → 本次临时追加，插入最内层，顺序变①②③④                    │
 * │      → 用途：RAG Advisor（只有 RAG 方法才需要）                  │
 * │             chatId 参数（每次不同，必须运行时传）                │
 * │                                                                  │
 * │  只有 .call() 才是真正的执行触发点：                             │
 * │    → 依次运行所有 Advisor 的 before()                           │
 * │    → 发 HTTP 请求给 AI                                          │
 * │    → 依次运行所有 Advisor 的 after()                            │
 * │    → 返回 CallResponseSpec，再 .chatResponse() 取结果           │
 * │                                                                  │
 * │  BEFORE 顺序执行 ①→②→③（发给 AI 之前）                         │
 * │    ① MessageChatMemoryAdvisor.before()                           │
 * │         chatId → repository.findByConversationId(chatId)         │
 * │         → 读出历史消息 → 塞进本次请求                            │
 * │         → 请求变成 [系统提示词] + [历史N条] + [当前消息]          │
 * │    ② SafeGuardAdvisor.before()                                   │
 * │         扫描"暴力/色情/赌博/hack/破解"                           │
 * │         → 有：立刻返回错误提示，后续全跳过（AI不被调用，省钱）    │
 * │         → 无：放行                                               │
 * │    ③ MyLoggerAdvisor.before()                                    │
 * │         打印完整请求内容，只记日志不修改                          │
 * │                                                                  │
 * │  HTTP 请求 → 通义千问 AI（耗时最长）                             │
 * │    发送：[系统提示词] + [历史N条] + [当前消息]                   │
 * │    AI 看到完整上下文，生成回复                                   │
 * │                                                                  │
 * │  AFTER 逆序执行 ③→②→①（AI 回复后收尾）                          │
 * │    逆序 = 洋葱模型：before 从外向内穿，after 从内向外弹           │
 * │    ③ MyLoggerAdvisor.after()    打印 token 用量 + AI 回复        │
 * │    ② SafeGuardAdvisor.after()   无操作                           │
 * │    ① MessageChatMemoryAdvisor.after()                            │
 * │         repository.saveAll(chatId, 全量消息列表)                  │
 * │         ①最外层最后执行，确保一切处理完毕后再持久化历史           │
 * │                                                                  │
 * │  .call() 返回 CallResponseSpec → .chatResponse() → 取文字        │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  五、RAG 扩展 — doChatWithLocalRag / doChatWithDashScopeRag / doChatWithPgVectorRag │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  比 doChat 多了第④个 Advisor（追加在最内层）：                    │
 * │  BEFORE 顺序 ①→②→③→④  AFTER 逆序 ④→③→②→①                      │
 * │                                                                  │
 * │  本地 RAG（doChatWithLocalRag）：                                │
 * │    ④ RetrievalAugmentationAdvisor（每次临时 build）              │
 * │       内部 VectorStoreDocumentRetriever：                        │
 * │         问题 → Embedding API → 向量                              │
 * │         → SimpleVectorStore 相似搜索 topK=3, threshold=0.5       │
 * │         → 把 Top3 文档拼进 Prompt                                │
 * │    知识库位置：JVM 内存，程序启动时向量化 documents/*.md          │
 * │    向量化在哪：本地调阿里云 Embedding API                         │
 * │                                                                  │
 * │  云端 RAG（doChatWithDashScopeRag）：                            │
 * │    ④ dashScopeRagAdvisor（Spring 容器单例 @Bean）               │
 * │       内部 DashScopeDocumentRetriever：                          │
 * │         问题 → 直接发给阿里云百炼                                 │
 * │         → 云端完成向量化+搜索，返回文档                          │
 * │         → 把文档拼进 Prompt                                      │
 * │    知识库位置：阿里云百炼控制台，控制台上传管理                   │
 * │    向量化在哪：全在云端，本地只发问题、收结果                     │
 * │                                                                  │
 * │  验证云端是否真的被调用（3种方法）：                              │
 * │    ① 控制台搜 [MyLogger] 请求消息 → user 消息后多了一段文档内容  │
 * │    ② 控制台搜 [DashScopeRAG]     → 出现说明本方法被调到          │
 * │    ③ 阿里云百炼控制台 → 恋爱大师 → 监控配置 → 调用次数 +1        │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  六、@Resource vs setter — 为什么 dashScopeRagAdvisor 用 setter │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  Spring 注解注入（@Resource / @Autowired）的前提：               │
 * │    这个类的实例必须由 Spring 容器创建（加了 @Component/@Service）  │
 * │    Spring 才会在创建对象后自动扫描字段注解并注入                  │
 * │                                                                  │
 * │  LoveApp 没有 @Component：                                       │
 * │    所有实例都是手动 new LoveApp(model) / new LoveApp(model, redis)│
 * │    手动 new 出来的对象 Spring 完全不知道，字段注解无效            │
 * │    → @Resource 写了也没用，字段永远是 null                       │
 * │                                                                  │
 * │  所以用 setter 注入：                                            │
 * │    测试类 LoveAppTest 有 @SpringBootTest → 是 Spring 管理的      │
 * │    它能用 @Autowired @Qualifier("dashScopeRagAdvisor") 拿到Bean  │
 * │    然后调 loveApp.setDashScopeRagAdvisor(bean) 传给 loveApp     │
 * │    → 间接完成"Spring Bean → 手动对象"的桥接                     │
 * │                                                                  │
 * │  @Resource vs @Autowired+@Qualifier 的规则（在 Spring 管理的类里）│
 * │    @Autowired：按类型找                                          │
 * │      同类型只有1个 → 直接注入，名字随便                          │
 * │      同类型有2个 → 必须加 @Qualifier("Bean名") 指定              │
 * │    @Resource：先按字段名找，找不到再按类型找                      │
 * │      字段名 == Bean名 → 直接命中，不需要 name 属性               │
 * │      字段名 != Bean名 → 按类型找（有歧义时加 @Resource(name="x")）│
 * │  本项目举例：                                                    │
 * │    容器有2个 EmbeddingModel → 必须 @Qualifier("dashscope...")     │
 * │    容器只有1个 VectorStore  → @Autowired 直接给，名字随便起      │
 * └─────────────────────────────────────────────────────────────────┘
 */
@Slf4j
public class LoveApp {

    private static final SystemPromptTemplate SYSTEM_PROMPT_TEMPLATE =
            new SystemPromptTemplate(new ClassPathResource("template/love-system.st"));

    private static final Map<String, Object> DEFAULT_PROMPT_VARS = Map.of(
            "years",     "8",
            "style",     "温柔耐心，像朋友倾听",
            "max_words", "300"
    );

    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final ChatClient chatClient;

    // 本地 RAG（SimpleVectorStore，内存版，通过 setter 注入）
    private VectorStore ragVectorStore;

    // 查询重写器（通过 setter 注入，原因见类注释六：LoveApp 手动 new，Spring 不管理它）
    private QueryRewriter queryRewriter;

    // 多查询扩展（通过 setter 注入）
    private LoveAppMultiQueryExpander multiQueryExpander;

    // 阿里云百炼 RAG Advisor（通过 setter 注入，原因见类注释六）
    private Advisor dashScopeRagAdvisor;

    // 所有工具（通过 setter 注入，由 ToolRegistration @Bean 提供）
    // LoveApp 是手动 new 的，@Resource 无效，需调用方注入
    private ToolCallback[] allTools;

    // MCP 工具提供者（通过 setter 注入）
    // Spring AI 启动时自动读取 mcp-servers.json，把 MCP 服务器的工具封装成 SyncMcpToolCallbackProvider
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    // ── 构造方法 ① 内存版 ────────────────────────────────────────────
    public LoveApp(ChatModel dashscopeChatModel) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        chatClient = buildChatClient(dashscopeChatModel, chatMemory);
    }

    // ── 构造方法 ② Redis 版 ──────────────────────────────────────────
    public LoveApp(ChatModel dashscopeChatModel, StringRedisTemplate redisTemplate) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new RedisChatMemoryRepository(redisTemplate, Duration.ofDays(7)))
                .maxMessages(20)
                .build();
        chatClient = buildChatClient(dashscopeChatModel, chatMemory);
    }

    // ── 构造方法 ③ 文件版 ────────────────────────────────────────────
    public LoveApp(ChatModel dashscopeChatModel, String fileDir) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new FileBasedChatMemoryRepository(fileDir))
                .maxMessages(20)
                .build();
        chatClient = buildChatClient(dashscopeChatModel, chatMemory);
    }

    // ── 构造方法 ④ MySQL 版 ──────────────────────────────────────────
    public LoveApp(ChatModel dashscopeChatModel, JdbcTemplate jdbcTemplate) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new MysqlChatMemoryRepository(jdbcTemplate))
                .maxMessages(20)
                .build();
        chatClient = buildChatClient(dashscopeChatModel, chatMemory);
    }

    private ChatClient buildChatClient(ChatModel model, MessageWindowChatMemory chatMemory) {
        Message systemMessage = SYSTEM_PROMPT_TEMPLATE.createMessage(DEFAULT_PROMPT_VARS);
        return ChatClient.builder(model)
                .defaultSystem(systemMessage.getText())
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        SafeGuardAdvisor.builder()
                                .sensitiveWords(java.util.List.of("暴力", "色情", "赌博", "hack", "破解"))
                                .failureResponse("您的消息包含违规内容，请重新描述")
                                .build(),
                        new MyLoggerAdvisor()
                        // new ReReadingAdvisor()  // 开启 Re2 提示词重复，提升推理质量，但多消耗 token
                )
                .build();
    }

    /** 普通对话：AI 凭自身训练数据回答，见类注释四 */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse();
        String content = chatResponse != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("chatId={} | user={} | ai={}", chatId, message, content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .stream()
                .content();
    }

    /**
     * Function Calling 对话：给大模型装上全套工具箱
     *
     * 和 doChat 唯一的区别就是多了 .tools(allTools)
     * allTools 由 ToolRegistration Bean 提供，包含6个工具：
     *   文件读写 / 联网搜索 / 网页抓取 / 资源下载 / 终端执行 / PDF生成
     *
     * 流程：
     *   ① 把工具清单（方法名+description）和用户问题一起发给大模型
     *   ② 大模型判断需不需要工具，需要哪个
     *   ③ 需要 → 返回工具调用意图（JSON），Spring AI 自动执行对应方法
     *   ④ 把工具结果塞回大模型，大模型生成最终回答
     *   不需要工具 → 直接回答，和普通对话一样
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                // allTools 为空时退化为普通对话（不注入则无工具能力）
                .toolCallbacks(allTools != null ? allTools : new ToolCallback[0])
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse();
        String content = chatResponse != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("[FunctionCalling] chatId={} | user={} | ai={}", chatId, message, content);
        return content;
    }

    public void setAllTools(ToolCallback[] allTools) {
        this.allTools = allTools;
    }

    public void setToolCallbackProvider(SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    // record = Java 14+ 简洁类，自动生成构造方法/getter/toString，代码量减少 90%
    record LoveReport(Integer score, String advice, String nextStep) {}

    /**
     * 结构化输出：返回 LoveReport 对象而非纯文字
     * .entity(LoveReport.class) = 自动追加 JSON 格式指令 + 解析 JSON → 对象
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport report = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("chatId={} | user={} | report={}", chatId, message, report);
        return report;
    }

    public void setRagVectorStore(VectorStore ragVectorStore) {
        this.ragVectorStore = ragVectorStore;
    }

    public void setQueryRewriter(QueryRewriter queryRewriter) {
        this.queryRewriter = queryRewriter;
    }

    public void setMultiQueryExpander(LoveAppMultiQueryExpander multiQueryExpander) {
        this.multiQueryExpander = multiQueryExpander;
    }

    /**
     * 本地 RAG：基于 SimpleVectorStore（documents/*.md 向量化结果）
     *
     * @param status 用户恋爱状态（单身 / 恋爱 / 已婚），传 null 则不过滤、搜全部文档
     *               传了 status → 只搜对应状态的文档，回答更精准
     *               不传（null）→ 15条文档全部参与搜索，可能混入无关内容
     *
     * 为什么需要 status？
     *   文档存入时已贴了 metadata.status 标签（见 LoveAppDocumentLoader）
     *   但标签不会自动生效，必须在搜索时显式传入 filterExpression 才能过滤
     *   LoveAppRagCustomAdvisorFactory 封装了这个过滤逻辑
     */
    public String doChatWithLocalRag(String message, String chatId, String status) {
        if (ragVectorStore == null) {
            log.warn("VectorStore 未注入，退化为普通对话");
            return doChat(message, chatId);
        }
        // ── Step 1：查询改写 ────────────────────────────────────────────
        // 把口语化问题改写成更适合向量搜索的专业表达，提高命中率
        // 例："她不回我消息咋整" → "恋爱中对方长时间不回复消息如何处理"
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);

        // ── Step 2：决定用哪个检索 Advisor ─────────────────────────────
        // ragVectorStore：内存向量库，启动时由 LocalMemoryVectorStoreConfig
        //   读取 documents/*.md，向量化后存入，共15条文档（单身5/恋爱5/已婚5）
        Advisor ragAdvisor;
        if (status != null && !status.isBlank()) {
            // 有 status：createLoveAppRagCustomAdvisor 内部加了 filterExpression(status==xxx)
            //   只在对应状态的5条文档里搜，单身问题不会搜到已婚建议
            ragAdvisor = LoveAppRagCustomAdvisorFactory
                    .createLoveAppRagCustomAdvisor(ragVectorStore, status);
        } else {
            // 无 status：搜全部15条文档
            RetrievalAugmentationAdvisor.Builder ragBuilder = RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(VectorStoreDocumentRetriever.builder()
                            .vectorStore(ragVectorStore)
                            .topK(3)           // 最多返回3条最相关文档
                            .similarityThreshold(0.5) // 相似度低于0.5直接丢弃
                            .build());
            if (multiQueryExpander != null) {
                // multiQueryExpander：把1个问题扩展成3个不同角度的子问题
                //   分别搜索后用 ConcatenationDocumentJoiner 合并去重
                //   召回更多相关文档，但会多消耗3倍的向量搜索次数
                ragBuilder.queryExpander(multiQueryExpander.buildExpander())
                        .documentJoiner(new ConcatenationDocumentJoiner());
            }
            ragAdvisor = ragBuilder.build();
        }

        // ── Step 3：发起对话 ────────────────────────────────────────────
        // advisors(ragAdvisor)：RAG 流程在这里触发，执行顺序：
        //   before →「搜索向量库，把相关文档拼入 prompt」
        //   call AI →「AI 拿到问题 + 文档，生成回答」
        //   after  →「记录日志」
        // advisors(chatId)：记忆 Advisor，把本轮对话存入历史，下次能记住
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(rewrittenMessage)
                .advisors(ragAdvisor)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse();
        String content = chatResponse != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("[LocalRAG] chatId={} status={} | user={} | ai={}", chatId, status, message, content);
        return content;
    }

    /**
     * 阿里云百炼 RAG：知识库托管在阿里云百炼控制台，云端完成向量化+搜索
     * 适合：不想自己管数据库，知识库内容在阿里云控制台上传维护
     * dashScopeRagAdvisor 由调用方通过 setter 注入（见类注释六原因）
     */
    public void setDashScopeRagAdvisor(Advisor advisor) {
        this.dashScopeRagAdvisor = advisor;
    }

    public String doChatWithDashScopeRag(String message, String chatId) {
        if (dashScopeRagAdvisor == null) {
            log.warn("[DashScopeRAG] 云端 Advisor 未注入，退化为本地 RAG");
            return doChatWithLocalRag(message, chatId,null);
        }
        log.info("[DashScopeRAG] 实际使用的 Advisor 类型: {}", dashScopeRagAdvisor.getClass().getName());
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(dashScopeRagAdvisor)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse();
        String content = chatResponse != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("[DashScopeRAG] chatId={} | user={} | ai={}", chatId, message, content);
        return content;
    }

    /**
     * pgVector RAG：知识库存在 Supabase pgVector，自己掌控数据
     * 适合：需要自定义知识库内容、数据隐私要求高、或数据量大的场景
     * supabasePgVectorRagAdvisor 由调用方通过 setter 注入（原因同 dashScopeRagAdvisor）
     */
    private Advisor supabasePgVectorRagAdvisor;
    private VectorStore supabasePgVectorStore; // 用于打印检索结果验证

    public void setSupabasePgVectorRagAdvisor(Advisor supabasePgVectorRagAdvisor) {
        this.supabasePgVectorRagAdvisor = supabasePgVectorRagAdvisor;
    }

    public void setSupabasePgVectorStore(VectorStore supabasePgVectorStore) {
        this.supabasePgVectorStore = supabasePgVectorStore;
    }

    public String doChatWithSupobasePgVectorRag(String message, String chatId) {
        if (supabasePgVectorRagAdvisor == null) {
            log.warn("[PgVectorRAG] supabasePgVectorRagAdvisor 未注入，退化为本地 RAG");
            return doChatWithLocalRag(message, chatId,null);
        }

        // ══ 验证：直接搜 Supabase，打印搜到的文档，证明数据来自 pgVector ══
        if (supabasePgVectorStore != null) {
            var docs = supabasePgVectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(message).topK(3).build());
            log.info("[PgVectorRAG] ★ Supabase 检索到 {} 条文档：", docs.size());
            for (int i = 0; i < docs.size(); i++) {
                log.info("[PgVectorRAG]   第{}条：{}", i + 1, docs.get(i).getText());
            }
        }

        log.info("[PgVectorRAG] 实际使用的 Advisor 类型: {}", supabasePgVectorRagAdvisor.getClass().getName());
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(supabasePgVectorRagAdvisor)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse();
        String content = chatResponse != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("[PgVectorRAG] chatId={} | user={} | ai={}", chatId, message, content);
        return content;
    }

    /**
     * ════════════════════════════════════════════════════════════════════════
     * doChatWithMcp —— 通过 MCP 外部服务器工具与 AI 对话
     * ════════════════════════════════════════════════════════════════════════
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  一、什么是 MCP？和普通工具调用有什么区别？                              │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     *  普通工具（doChatWithTools）：
     *    工具 = 本地 Java 方法，例如 FileOperationTool.saveFile()
     *    运行在：同一个 JVM 进程里
     *    注册方式：@Tool 注解 → .tools(obj)
     *
     *  MCP 工具（本方法）：
     *    工具 = 外部独立进程（Node.js 子进程）提供的能力
     *    运行在：独立进程，通过 stdio 管道与 Java 通信
     *    注册方式：mcp-servers.json → .toolCallbacks(provider)
     *
     *  两者本质完全相同：都是 Function Calling（AI 决定调哪个工具、传什么参数）
     *  唯一差别：工具代码跑在 Java 进程里，还是跑在外部子进程里。
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  二、启动时发生了什么？（Spring 上下文初始化阶段）                        │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     *  Step 1  Spring 读取 resources/mcp-servers.json：
     *          {
     *            "filesystem": { "command": "npx", "args": ["@modelcontextprotocol/server-filesystem", "mcp/tmp"] },
     *            "memory":     { "command": "npx", "args": ["@modelcontextprotocol/server-memory"] }
     *          }
     *
     *  Step 2  Spring AI 对每个服务器执行：
     *            Process p = Runtime.getRuntime().exec("npx -y @modelcontextprotocol/server-filesystem mcp/tmp")
     *          → 启动一个独立的 Node.js 子进程（后台一直运行）
     *          → 通过 stdin/stdout 管道与 Java 通信（这就是 stdio 传输协议）
     *
     *  Step 3  MCP 握手（initialize 协议）：
     *            Java → 子进程：{"method":"initialize","params":{...}}
     *            子进程 → Java：{"result":{"capabilities":{"tools":{...}}}}
     *          → 子进程告诉 Java：我有哪些工具、工具叫什么名字、需要什么参数
     *
     *  Step 4  Spring AI 把所有工具包装成 ToolCallback 对象，
     *          注入到 SyncMcpToolCallbackProvider Bean 里。
     *          此时 toolCallbackProvider 里已经有：
     *            filesystem/read_file   → 读取文件
     *            filesystem/write_file  → 写入文件
     *            memory/create_entities → 创建记忆节点
     *            memory/search_nodes    → 搜索记忆
     *            ... 等若干个工具
     *
     *  ✅ 验证方法1：看启动日志，能看到：
     *       INFO StdioClientTransport : MCP server started
     *       INFO StdioClientTransport : STDERR Message received: Secure MCP Filesystem Server running on stdio
     *     说明子进程已成功启动并握手完成。
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  三、调用时发生了什么？（本方法执行阶段）                                 │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     *  用户输入："帮我创建一个文件 love-note.txt，内容是：亲爱的，今天..."
     *
     *  Step A  .toolCallbacks(toolCallbackProvider)
     *          → 把 toolCallbackProvider 里所有工具的名称+参数描述，
     *            拼成 JSON 追加到发给 AI 的请求里，格式如下：
     *            {
     *              "tools": [
     *                {
     *                  "name": "filesystem_write_file",
     *                  "description": "Create or overwrite a file with content",
     *                  "parameters": { "path": "string", "content": "string" }
     *                },
     *                {
     *                  "name": "filesystem_read_file",
     *                  "description": "Read the complete contents of a file",
     *                  "parameters": { "path": "string" }
     *                },
     *                ...
     *              ]
     *            }
     *          ⚠️  注意：这里是 .toolCallbacks()，不是 .tools()
     *              .tools(obj)       → 从 @Tool 注解的 Java 方法提取工具描述（适合本地工具）
     *              .toolCallbacks(p) → 直接用已封装好的 ToolCallback 数组（适合 MCP 工具）
     *              如果传 MCP Provider 给 .tools()，会因找不到 @Tool 注解而报错：
     *              "No @Tool annotated methods found in SyncMcpToolCallbackProvider"
     *
     *  Step B  AI（大模型）收到消息后，识别出意图是"写文件"：
     *            → 返回 tool_use 响应（而不是直接回答文字）：
     *              {
     *                "tool_calls": [{
     *                  "name": "filesystem_write_file",
     *                  "arguments": {
     *                    "path": "mcp/tmp/love-note.txt",
     *                    "content": "亲爱的，今天和你在一起很开心，我喜欢你。"
     *                  }
     *                }]
     *              }
     *
     *  Step C  Spring AI 收到 tool_use 响应，找到对应的 ToolCallback，执行它：
     *            → 通过 stdin 给 filesystem 子进程发消息：
     *              {"method":"tools/call","params":{"name":"write_file","arguments":{"path":"...","content":"..."}}}
     *            → 子进程真正在磁盘上写文件
     *            → 子进程通过 stdout 返回结果：
     *              {"result":{"content":[{"type":"text","text":"Successfully wrote ..."}]}}
     *
     *  Step D  Spring AI 把工具返回结果追加到对话历史，再次调用 AI：
     *            "工具返回：Successfully wrote love-note.txt"
     *            → AI 根据工具结果生成最终自然语言回复：
     *              "已创建文件 love-note.txt，内容是：亲爱的，今天和你在一起很开心，我喜欢你。"
     *
     *  ✅ 验证方法2：看 MyLoggerAdvisor 输出的日志：
     *       [MyLogger] AI 响应: 已创建文件：`love-note.txt`
     *     说明 AI 确实调用了工具并得到了结果。
     *
     *  ✅ 验证方法3：直接看磁盘文件是否被创建：
     *       cat 08-yu-ai-agent/mcp/tmp/love-note.txt
     *     能看到文件内容，说明 MCP 工具真的执行了写文件操作。
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  四、整体调用链路图                                                    │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     *   测试代码
     *      │  loveApp.doChatWithMcp("帮我创建文件...", chatId)
     *      ▼
     *   LoveApp.doChatWithMcp()
     *      │  chatClient.prompt().toolCallbacks(toolCallbackProvider).call()
     *      ▼
     *   Spring AI ChatClient（组装请求：消息 + 工具描述列表）
     *      │  HTTP POST https://api.openai.com/v1/chat/completions
     *      ▼
     *   AI 大模型（识别意图 → 决定调 write_file 工具）
     *      │  返回 tool_calls: [{name:"filesystem_write_file", args:{...}}]
     *      ▼
     *   Spring AI ToolCallingManager（找到对应 ToolCallback，执行它）
     *      │  stdin: {"method":"tools/call","params":{"name":"write_file",...}}
     *      ▼
     *   Node.js 子进程（@modelcontextprotocol/server-filesystem）
     *      │  磁盘写文件：mcp/tmp/love-note.txt
     *      │  stdout: {"result":{"content":[{"text":"Successfully wrote..."}]}}
     *
     *   Spring AI（把工具结果加入对话历史，再次请求 AI 生成最终回答）
     *      │  HTTP POST（带工具结果）
     *      ▼
     *   AI 大模型（生成自然语言回复）
     *      │  "已创建文件 love-note.txt，内容是：..."
     *      ▼
     *   返回给调用方
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .advisors(new MyLoggerAdvisor())
                // ⚡ 关键：把 MCP 工具注册到本次请求
                //    toolCallbackProvider 里装着 filesystem/memory 两个 MCP 服务器的所有工具
                //    必须用 .toolCallbacks() 而非 .tools()：
                //      .tools(obj)       → 扫描 @Tool 注解，适合本地 Java 对象
                //      .toolCallbacks(p) → 直接用封装好的 ToolCallback，适合 MCP/外部工具
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("[MCP] chatId={} | user={} | ai={}", chatId, message, content);
        return content;
    }
}

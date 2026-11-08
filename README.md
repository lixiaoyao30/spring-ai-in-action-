# 🤖 Spring AI 全链路实战：yu-ai-agent

> **Java + Spring AI + RAG + Function Calling + MCP + ReAct Agent**
> 
> 一个覆盖 AI 应用开发全链路的实战项目，附系统学习笔记 [read.md](./read.md)

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-blue)](https://spring.io/projects/spring-ai)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.0.0-purple)](https://github.com/langchain4j/langchain4j)

---

## 📚 目录

- [项目概览](#项目概览)
- [技术栈](#技术栈)
- [知识点地图](#知识点地图)
- [项目结构](#项目结构)
- [核心架构图](#核心架构图)
- [Java & Spring Boot 基础速查](#java--spring-boot-基础速查)
- [Spring AI 核心速查](#spring-ai-核心速查)
- [RAG 与向量数据库](#rag-与向量数据库)
- [Function Calling 工具调用](#function-calling-工具调用)
- [MCP 协议](#mcp-协议)
- [ReAct Agent 智能体](#react-agent-智能体)
- [SSE 流式输出](#sse-流式输出)
- [快速启动](#快速启动)
- [深度学习笔记](#深度学习笔记)

---

## 项目概览

本项目 `yu-ai-agent` 是一个 **AI 智能体（Agent）** 完整实现，以"恋爱大师"应用为载体，
从零构建涵盖 Spring AI 全链路的实战项目：

```
底层 API 调用 → Spring AI 封装 → 对话记忆 → RAG检索增强
    → Function Calling → MCP协议 → ReAct Agent → SSE流式前端
```

**两个核心应用：**
- `LoveApp`：恋爱大师，演示 Prompt / 记忆 / RAG / MCP 全套能力
- `YuManus`：ReAct 智能体，演示多工具协作、自主规划任务

---

## 技术栈

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | Record、虚拟线程、sealed class |
| 框架 | Spring Boot 3.x | IoC/DI、自动配置、starter 机制 |
| AI 框架 | Spring AI 1.0 | ChatClient、Advisor、RAG、Tools |
| AI 框架 | Spring AI Alibaba | DashScope/通义千问 集成 |
| AI 框架 | LangChain4j 1.0.0 | 对比学习，底层 API 调用 |
| 向量数据库 | SimpleVectorStore | 本地内存向量存储 |
| 向量数据库 | PgVector (Supabase) | PostgreSQL 向量扩展，云端存储 |
| 向量数据库 | DashScope 云 | 阿里云托管向量存储 |
| 记忆存储 | InMemory | JVM 内存，开发调试用 |
| 记忆存储 | Redis | 分布式，TTL 7天 |
| 记忆存储 | 文件系统 | JSON 持久化 |
| 记忆存储 | MySQL | 关系型持久化 |
| 协议 | MCP (stdio/SSE) | Model Context Protocol |
| 前端 | Vue3 + Vite | SSE 流式对话界面 |

---

## 知识点地图

```
┌─────────────────────────────────────────────────────────┐
│                    Java & Spring Boot                    │
│  IoC/DI · @Bean · @Component · @Autowired · AOP         │
│  @RestController · @Service · @Repository · 分层架构    │
│  @Async · CompletableFuture · Flux(响应式)              │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                  Spring AI 核心模型                      │
│                                                         │
│  ChatModel ←→ ChatClient (Fluent API)                   │
│       │              │                                  │
│   底层直调        Advisor 洋葱模型                       │
│   (send/stream)   (前置/后置增强)                        │
└──────────────────────────┬──────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼──────┐  ┌────────▼───────┐  ┌───────▼──────┐
│ Prompt 工程   │  │   对话记忆      │  │     RAG      │
│              │  │               │  │              │
│ SystemPrompt │  │ InMemory      │  │ ETL文档处理   │
│ UserPrompt   │  │ Redis(TTL7天) │  │ Embedding    │
│ PromptTemplate│  │ File(JSON)   │  │ VectorStore  │
│ @SystemPrompt│  │ MySQL        │  │ 相似度检索    │
└──────────────┘  └───────────────┘  │ QueryRewrite │
                                     │ MultiQuery   │
                                     └──────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼──────┐  ┌────────▼───────┐  ┌───────▼──────┐
│Function Call  │  │     MCP       │  │ ReAct Agent  │
│              │  │               │  │              │
│ @Tool        │  │ stdio模式     │  │ BaseAgent    │
│ @ToolParam   │  │ SSE模式       │  │ ReActAgent   │
│ 6个工具       │  │ JSON-RPC握手  │  │ ToolCallAgent│
│ ToolCallback │  │ 5个MCP服务器  │  │ YuManus      │
│ ToolRegistra-│  │ MethodToolCB  │  │ AgentState   │
│ tion         │  │ SyncMcpToolCB │  │ 模板方法模式  │
└──────────────┘  └───────────────┘  └──────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│              Web 层 & 前端                               │
│  SSE (Flux/ServerSentEvent/SseEmitter 三种方式)          │
│  CORS跨域配置 · AiController · Vue3 前端                │
└─────────────────────────────────────────────────────────┘
```

---

## 项目结构

```
08-yu-ai-agent/
├── src/main/java/com/yupi/yuaiagent/
│   ├── YuAiAgentApplication.java       # Spring Boot 启动类
│   ├── app/
│   │   ├── LoveApp.java                # 恋爱大师应用（核心）
│   │   └── LoveAppConfig.java          # Spring @Bean 配置
│   ├── agent/
│   │   ├── BaseAgent.java              # Agent基类：run()/runStream()循环控制
│   │   ├── ReActAgent.java             # ReAct模式：think()+act()抽象方法
│   │   ├── ToolCallAgent.java          # 工具调用实现：核心 think/act 逻辑
│   │   ├── YuManus.java                # 顶层Agent：systemPrompt + nextStepPrompt
│   │   ├── AgentException.java
│   │   └── model/AgentState.java       # IDLE/RUNNING/FINISHED/ERROR 四状态
│   ├── advisor/
│   │   ├── MyLoggerAdvisor.java        # 自定义日志 Advisor
│   │   └── ReReadingAdvisor.java       # Re-reading Advisor（提升推理）
│   ├── tools/
│   │   ├── ToolRegistration.java       # 6个工具统一注册 @Bean
│   │   ├── WebSearchTool.java          # 网络搜索
│   │   ├── WebScrapingTool.java        # 网页爬取
│   │   ├── FileOperationTool.java      # 文件读写
│   │   ├── ResourceDownloadTool.java   # 资源下载
│   │   ├── TerminalOperationTool.java  # 终端命令
│   │   └── PDFGenerationTool.java      # PDF生成
│   ├── rag/
│   │   ├── LocalMemoryVectorStoreConfig.java   # 本地向量存储
│   │   ├── SupabasePgVectorStoreConfig.java    # PgVector云端
│   │   ├── DashScopeRagAdvisorConfig.java      # DashScope云端RAG
│   │   ├── LoveAppDocumentLoader.java           # ETL：文档加载→切割→存储
│   │   ├── MyTokenTextSplitter.java             # 自定义文本分割器
│   │   ├── MyKeywordEnricher.java               # 关键词元数据增强
│   │   └── query/
│   │       ├── QueryRewriter.java               # 查询改写
│   │       ├── LoveAppMultiQueryExpander.java   # 多查询扩展
│   │       └── LoveAppContextualQueryAugmenterFactory.java
│   ├── memory/
│   │   ├── FileBasedChatMemoryRepository.java  # 文件存储实现
│   │   ├── RedisChatMemoryRepository.java      # Redis存储实现
│   │   └── MysqlChatMemoryRepository.java      # MySQL存储实现
│   ├── controller/
│   │   ├── AiController.java           # 5个REST接口
│   │   └── HealthController.java
│   ├── config/
│   │   └── CorsConfig.java             # CORS跨域配置
│   └── demo/invoke/                    # 底层API调用演示
│       ├── SpringAiInvoke.java         # Spring AI基础调用
│       ├── LangChainAiInvoke.java      # LangChain4j对比
│       ├── SdkAiTextInvoke.java        # SDK直接调用
│       └── HttpAiInvoke.java           # HTTP直接调用
├── yu-ai-agent-frontend/               # Vue3 前端
│   └── src/views/
│       ├── LoveAppPage.vue             # 恋爱大师对话页
│       └── ManusPage.vue               # Agent任务执行页
├── yu-image-search-mcp-server/         # 自定义 MCP 服务（图片搜索）
├── src/main/resources/
│   ├── application.yml                 # 主配置（端口8123，上下文/api）
│   ├── mcp-servers.json                # 5个MCP服务器配置
│   ├── sql/init.sql                    # MySQL对话记忆建表SQL
│   └── template/love-system.st         # 系统Prompt模板文件
└── read.md                             # 📖 系统学习笔记（2000+行）
```

---

## 核心架构图

### LoveApp 构造方法 × 四种记忆模式

```java
// 内存版（默认，开发用）
new LoveApp(chatModel)

// Redis版（生产推荐，TTL 7天）
new LoveApp(chatModel, redisTemplate)

// 文件版（无外部依赖，JSON持久化）
new LoveApp(chatModel, fileBasedChatMemoryRepo)

// MySQL版（关系型持久化）
new LoveApp(chatModel, mysqlChatMemoryRepo)
```

### Advisor 洋葱模型

```
请求进入
    │
    ▼
[MyLoggerAdvisor]  ← 自定义：打印请求/响应日志
    │
    ▼
[MessageChatMemoryAdvisor]  ← 自动注入历史消息 + 保存新消息
    │
    ▼
[QuestionAnswerAdvisor]     ← RAG：向量检索 + 上下文注入
    │
    ▼
    AI 模型
    │
    ▼ (结果原路返回，经过每层后置处理)
```

### Agent 四层继承

```
BaseAgent（抽象）
  └── ReActAgent（抽象）: think() + act() 抽象方法
        └── ToolCallAgent（具体）: 实现 think/act，管理工具调用
              └── YuManus（具体）: 设置 systemPrompt + nextStepPrompt
```

### AgentState 状态机

```
IDLE ──run()──→ RUNNING ──任务完成──→ FINISHED
                  │
                  └──异常──→ ERROR
```

---

## Java & Spring Boot 基础速查

### IoC 控制反转 / DI 依赖注入

```java
// 把对象交给 Spring 容器管理（三种方式）
@Component      // 通用组件
@Service        // 业务层
@Repository     // 数据层
@Controller     // 控制层

// 注入已有的 Bean（三种方式）
@Autowired              // 按类型注入
@Resource               // 按名称注入（Jakarta）
@Qualifier("beanName")  // 同类型多个Bean时，精确指定
```

**为什么用 IoC？**
> 不用 `new`，由 Spring 统一创建和管理对象，解耦依赖，方便测试和替换。

### @Bean 手动注册

```java
@Configuration
public class LoveAppConfig {
    // LoveApp 没有 @Component，必须手动注册
    @Bean
    public LoveApp loveApp(@Qualifier("openAiChatModel") ChatModel model) {
        return new LoveApp(model);  // Spring 管理生命周期
    }
}
```

### 分层架构（Controller → Service → Repository）

```java
// 1. Controller：接收请求，立即委托 Service
@RestController
@RequestMapping("/ai")
public class AiController {
    @Resource private LoveApp loveApp;

    @GetMapping("/love_app/chat/sync")
    public BaseResponse<String> chat(String message, String chatId) {
        return BaseResponse.success(loveApp.doChat(message, chatId));
    }
}
```

### 响应式编程：Flux

```java
// Flux = 异步数据流，一个数据一个数据地推送
// 类比：List是"一箱货物一次送达"，Flux是"快递员每件单独送"

Flux<String> stream = chatClient.prompt(...)
    .stream()
    .content();  // 返回字符流，每个字符单独推送
```

### CompletableFuture 异步

```java
// Agent 用异步线程执行，不阻塞主线程
CompletableFuture.runAsync(() -> {
    // 在后台线程执行 Agent 循环
    while (state == RUNNING && steps < maxSteps) {
        boolean needAct = think();
        if (needAct) act();
    }
});
// 立即返回 SseEmitter，主线程不等待
return sseEmitter;
```

### AOP 切面编程

```java
// Advisor 本质就是 AOP：在方法前后插入逻辑
// Spring AI 的 Advisor = 围绕 AI 调用的 AOP 切面
public class MyLoggerAdvisor implements CallAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        log.info("请求：{}", request.userText());    // 前置：记录请求
        AdvisedResponse response = chain.nextAroundCall(request);  // 调用下一层
        log.info("响应：{}", response.response());  // 后置：记录响应
        return response;
    }
}
```

---

## Spring AI 核心速查

### ChatClient vs ChatModel

```java
// ChatModel：底层接口，直接调用
ChatResponse response = chatModel.call(new Prompt("你好"));

// ChatClient：高级封装，Fluent API，推荐使用
String result = ChatClient.create(chatModel)
    .prompt()
    .user("你好")
    .call()
    .content();
```

### ChatClient Builder（完整链式调用）

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultSystem("你是恋爱大师，专门提供情感建议")     // 默认系统提示词
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(chatMemory),      // 记忆 Advisor
        new QuestionAnswerAdvisor(vectorStore),        // RAG Advisor
        new MyLoggerAdvisor()                          // 日志 Advisor
    )
    .build();

// 调用
String answer = chatClient.prompt()
    .user(userMessage)
    .advisorParam(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)  // 记忆会话ID
    .call()
    .content();
```

### Prompt 工程

```java
// 1. 直接字符串
chatClient.prompt().user("帮我写一首诗").call().content();

// 2. PromptTemplate（变量替换）
PromptTemplate template = new PromptTemplate("你是{role}，请帮助{user}");
Prompt prompt = template.create(Map.of("role", "顾问", "user", "小明"));

// 3. 从文件加载（.st 文件）
@Value("classpath:template/love-system.st")
private Resource systemPromptTemplate;
// love-system.st 内容：你是一位专业的恋爱顾问，擅长...
```

### 对话记忆（四种）

```java
// 1. 内存（开发用）
ChatMemory memory = MessageWindowChatMemory.builder()
    .maxMessages(10)  // 保留最近10条
    .build();         // 默认 InMemoryChatMemoryRepository

// 2. Redis（生产用，TTL 7天）
ChatMemory memory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(new RedisChatMemoryRepository(redisTemplate))
    .maxMessages(10)
    .build();

// 3. 文件（JSON持久化）
ChatMemory memory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(new FileBasedChatMemoryRepository())
    .maxMessages(10)
    .build();

// 4. MySQL
ChatMemory memory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(new MysqlChatMemoryRepository(jdbcTemplate))
    .maxMessages(10)
    .build();
```

**记忆工作原理：**
> `MessageChatMemoryAdvisor` 在每次请求前，自动从存储中取出历史消息，拼进 Prompt；
> 请求完成后，自动把本轮对话（用户消息 + AI回复）保存回存储。

---

## RAG 与向量数据库

### RAG 完整流程

```
【建库阶段（ETL）】
文档(.md/.pdf) → 加载(DocumentLoader) → 切割(TextSplitter)
             → Embedding(文本→1024维向量) → 存入VectorStore

【查询阶段】
用户问题 → [可选: 查询改写/多查询扩展]
         → Embedding(问题→向量)
         → 向量相似度检索(Top-K，余弦相似度)
         → 取出相关文档块
         → 拼入Prompt("参考以下资料：{context}\n\n用户问题：{question}")
         → AI 生成答案
```

### 三种向量存储对比

```java
// 1. 本地内存（零配置，开发用）
SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

// 2. PgVector / Supabase（PostgreSQL扩展，生产推荐）
// 需要在 Supabase 创建 pgvector 扩展 + 向量表（1024维）
PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
    .dimensions(1024)
    .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
    .build();

// 3. DashScope 云端（阿里云托管）
// 直接配置 API key，无需本地向量库
// application.yml: spring.ai.alibaba.dashscope.embedding.options.model: text-embedding-v3
```

### ETL 文档处理

```java
@Component
public class LoveAppDocumentLoader {

    public void loadDocuments(VectorStore vectorStore) {
        // 1. 加载文档
        List<Document> docs = new TokenTextSplitter()
            .split(new MarkdownDocumentReader("classpath:documents/love.md").read());

        // 2. 添加元数据（用于过滤）
        docs.forEach(doc -> doc.getMetadata().put("category", "love-advice"));

        // 3. 存入向量库（自动 Embedding）
        vectorStore.add(docs);
    }
}
```

### 高级 RAG 技巧

```java
// 查询改写（让问题更清晰）
public class QueryRewriter {
    public String rewrite(String originalQuery) {
        return chatClient.prompt()
            .user("将以下问题改写得更清晰：" + originalQuery)
            .call().content();
    }
}

// 多查询扩展（一个问题变多个，覆盖更广）
public class LoveAppMultiQueryExpander implements QueryExpander {
    @Override
    public List<Query> expand(Query query) {
        // 让 AI 从不同角度生成3个相关问题，并行检索，合并结果
        String prompt = "基于问题：" + query.text() + "，从不同角度生成3个相关问题";
        // ...解析返回的3个问题...
    }
}
```

---

## Function Calling 工具调用

### 定义工具（@Tool + @ToolParam）

```java
public class WebSearchTool {

    @Tool(description = "搜索互联网获取最新信息，适合查询时事、天气、新闻等")
    public String search(
            @ToolParam(description = "搜索关键词") String query) {
        // 调用搜索 API，返回搜索结果
        return searchApi.search(query);
    }
}
```

### 注册工具（ToolRegistration）

```java
@Configuration
public class ToolRegistration {

    @Bean
    public ToolCallback[] allTools() {
        // ToolCallbacks.from() 把 Java 对象封装成 ToolCallback
        // 之后必须用 .toolCallbacks() 而不是 .tools()
        return ToolCallbacks.from(
            new WebSearchTool(),        // 网络搜索
            new WebScrapingTool(),      // 网页爬取
            new FileOperationTool(),    // 文件读写
            new ResourceDownloadTool(), // 资源下载
            new TerminalOperationTool(),// 终端命令
            new PDFGenerationTool()     // PDF生成
        );
    }
}
```

### 两种使用方式对比

```java
// ❌ 错误：.tools() 期望原始 Java 对象（带 @Tool 注解的类实例）
chatClient.prompt().tools(new WebSearchTool()).call();

// ✅ 正确：已经 ToolCallbacks.from() 封装过了，必须用 .toolCallbacks()
chatClient.prompt().toolCallbacks(allTools).call();

// 报错提示：No @Tool annotated methods found in MethodToolCallback{...}
// → 说明用了 .tools() 但传入的是已封装的 ToolCallback 对象
```

### Function Calling 完整流程

```
用户："帮我搜索上海今天的天气"
    ↓
Spring AI 把工具的 JSON Schema 发给 AI（告诉 AI 有哪些工具可用）
    ↓
AI 回复：{"tool": "search", "args": {"query": "上海天气 2024"}}
    ↓
Spring AI 执行 search("上海天气 2024")  →  返回搜索结果
    ↓
把搜索结果放回 Prompt，AI 生成最终回答
    ↓
"上海今天晴，最高26度，最低18度..."
```

### 禁用自动工具执行（Agent 手动控制）

```java
// Agent 需要手动控制 think/act 分离，必须禁用自动执行
ChatOptions chatOptions = DashScopeChatOptions.builder()
    .withInternalToolExecutionEnabled(false)  // 禁用！
    .build();

// 这样 AI 返回工具调用指令后，Spring AI 不会自动执行
// 而是把"AI 要调哪些工具"的信息返回给我们，由 act() 手动执行
```

---

## MCP 协议

### 什么是 MCP

> **Model Context Protocol（模型上下文协议）** —— Anthropic 提出的开放标准。
> 作用：让 AI 模型通过标准协议调用外部工具/服务，类似 USB 接口的统一标准。

```
AI 应用（MCP Client）
    │
    │  JSON-RPC 2.0 协议
    │  ① 握手：initialize → capabilities 协商
    │  ② 工具列表：tools/list → 返回可用工具
    │  ③ 调用：tools/call → 返回结果
    │
    ▼
MCP Server（工具服务）
    ├── filesystem  （文件操作）
    ├── memory      （记忆存储）
    ├── fetch       （网页获取）
    ├── puppeteer   （浏览器控制）
    └── yu-image-search（Pixabay图片搜索，自研）
```

### 两种传输模式

```
stdio 模式（本地进程通信）          SSE 模式（网络通信）
─────────────────────              ──────────────────────
启动子进程 node server.js          HTTP 长连接 + 事件流
通过 stdin/stdout 通信             支持远程部署
适合本地工具（文件系统等）           适合微服务架构
```

### 配置（mcp-servers.json）

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "yu-image-search-mcp-server": {
      "command": "java",
      "args": ["-jar", "yu-image-search-mcp-server.jar"],
      "env": { "PIXABAY_API_KEY": "your-key" }
    }
  }
}
```

### Spring AI 中使用 MCP

```java
// application.yml 启用 MCP
spring.ai.mcp.client.enabled: true

// 注入 MCP 工具（两种类型）
@Resource
private List<McpSyncClient> mcpSyncClients;

// 方式1：MethodToolCallbackProvider（支持 @Tool 注解服务）
MethodToolCallbackProvider provider = MethodToolCallbackProvider
    .builder().toolObjects(mcpClient).build();

// 方式2：SyncMcpToolCallbackProvider（标准 MCP 协议服务）
SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(mcpSyncClients);
ToolCallback[] mcpTools = provider.getToolCallbacks();
```

---

## ReAct Agent 智能体

### ReAct 模式（Reasoning + Acting）

```
用户任务："帮我搜索 Spring AI 最新资料并保存为 PDF"
    ↓
【第1步 think】AI 思考：需要先搜索
    → 选择工具：webSearch("Spring AI 2024")
【第1步 act】执行搜索 → 返回搜索结果
    ↓
【第2步 think】AI 思考：有了搜索结果，需要爬取内容
    → 选择工具：webScraping(url)
【第2步 act】执行爬取 → 返回网页内容
    ↓
【第3步 think】AI 思考：内容已获取，生成 PDF
    → 选择工具：pdfGeneration(content)
【第3步 act】生成 PDF → 保存到本地
    ↓
【第4步 think】AI 思考：任务完成
    → 选择工具：doTerminate("任务完成")
【第4步 act】调用 terminate → AgentState = FINISHED → 退出循环
```

### BaseAgent 核心循环

```java
public abstract class BaseAgent {
    protected int maxSteps = 10;  // 防止无限循环
    protected AgentState state;
    protected List<Message> messageList;  // 完整对话历史

    public SseEmitter runStream(String userInput) {
        SseEmitter emitter = new SseEmitter();

        CompletableFuture.runAsync(() -> {
            messageList.add(new UserMessage(userInput));
            state = RUNNING;
            int steps = 0;

            while (state == RUNNING && steps++ < maxSteps) {
                String result = step();          // 每步 think + act
                emitter.send(result);            // 实时推送给前端
            }
            emitter.complete();
        });

        return emitter;  // 立即返回，不等 Agent 完成
    }
}
```

### ToolCallAgent.think() 核心逻辑

```java
@Override
public boolean think() {
    // 1. 加入提示词，提醒 AI 主动选工具
    messageList.add(new UserMessage(nextStepPrompt));

    // 2. 调用 AI，附带工具清单
    ChatResponse response = chatClient.prompt(new Prompt(messageList, chatOptions))
        .system(systemPrompt)
        .toolCallbacks(availableTools)   // 注意：已封装，用 toolCallbacks
        .call().chatResponse();

    // 3. 获取 AI 选择的工具列表
    List<ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();

    if (toolCalls.isEmpty()) {
        messageList.add(response.getResult().getOutput());
        return false;  // 无需执行 act
    }
    return true;  // 有工具要执行
}
```

---

## SSE 流式输出

### 三种实现方式对比

```java
// 方式1：Flux<String>（响应式，最简洁）
@GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatSSE(String message, String chatId) {
    return loveApp.doChatByStream(message, chatId);
    // 浏览器接收到的每条数据格式：data: 你好\n\n
}

// 方式2：Flux<ServerSentEvent>（带元数据，支持断线重连）
@GetMapping("/chat/server_sent_event")
public Flux<ServerSentEvent<String>> chatWithMeta(String message, String chatId) {
    return loveApp.doChatByStream(message, chatId)
        .map(chunk -> ServerSentEvent.<String>builder()
            .id(UUID.randomUUID().toString())  // 用于断线重连
            .event("message")                  // 事件类型
            .data(chunk)
            .build());
    // 浏览器接收格式：id: xxx\nevent: message\ndata: 你好\n\n
}

// 方式3：SseEmitter（Servlet体系，手动控制）
@GetMapping("/chat/sse_emitter")
public SseEmitter chatEmitter(String message, String chatId) {
    SseEmitter emitter = new SseEmitter(180000L);  // 3分钟超时
    loveApp.doChatByStream(message, chatId)
        .subscribe(
            chunk -> emitter.send(chunk),          // 收到数据 → 推送
            emitter::completeWithError,            // 异常 → 关闭
            emitter::complete                      // 完成 → 正常关闭
        );
    return emitter;  // 立即返回通道，后台持续推送
}
```

### Vue3 前端接收 SSE

```javascript
// EventSource：浏览器原生 SSE 客户端
const eventSource = new EventSource(
    `/api/ai/love_app/chat/sse?message=${msg}&chatId=${chatId}`
);

eventSource.onmessage = (event) => {
    responseText.value += event.data;  // 逐字追加显示
};

eventSource.onerror = () => {
    eventSource.close();  // 出错关闭连接
};
```

### CORS 跨域配置

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins("http://localhost:5173")  // Vue3 开发服务器
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowCredentials(true);
    }
}
```

---

## 快速启动

### 前置条件

```bash
# Java 21
java -version

# Maven 3.8+
mvn -version

# Node.js 18+（前端 + MCP 服务器）
node -version
```

### 配置（不要提交到 Git）

```yaml
# src/main/resources/application-local.yml（已在 .gitignore 中）
spring:
  ai:
    dashscope:
      api-key: sk-your-dashscope-api-key  # 从 bailian.console.aliyun.com 获取
    openai:
      api-key: your-openai-api-key
      base-url: http://localhost:6655/openai  # 或直接用 OpenAI 官方地址
```

### 启动后端

```bash
# 进入项目目录
cd 08-yu-ai-agent

# 跳过测试快速启动
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 或打包后运行
mvn package -Dmaven.test.skip=true
java -jar target/yu-ai-agent-*.jar --spring.profiles.active=local
```

### 启动前端

```bash
cd yu-ai-agent-frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

### API 接口列表

| 接口 | 方式 | 说明 |
|------|------|------|
| `GET /api/ai/love_app/chat/sync` | 同步 | 参数：`message`, `chatId` |
| `GET /api/ai/love_app/chat/sse` | SSE Flux | 流式，参数同上 |
| `GET /api/ai/love_app/chat/server_sent_event` | SSE 带元数据 | 支持断线重连 |
| `GET /api/ai/love_app/chat/sse_emitter` | SseEmitter | Servlet体系流式 |
| `GET /api/ai/manus/chat` | Agent SSE | 参数：`message` |

---

## 深度学习笔记

详细的系统学习笔记在 [read.md](./read.md)，包含：

| 章节 | 内容 |
|------|------|
| Ch0 项目全景 | 技术栈、模块关系、学习路径 |
| Ch1 Spring Boot 前置 | IoC/DI/AOP 原理、注解详解 |
| Ch2 底层 API 调用 | OpenAI SDK、LangChain4j、HTTP直调对比 |
| Ch3 Spring AI 核心 | ChatModel vs ChatClient 详解 |
| Ch4 Prompt + Advisor | 提示词工程、Advisor洋葱模型 |
| Ch5 对话记忆 | 四种存储实现原理与代码 |
| Ch6 RAG | ETL、Embedding、向量库、检索策略 |
| Ch7 Function Calling | 工具定义、注册、调用全流程 |
| Ch8 MCP | 协议原理、两种模式、自定义服务器 |
| Ch9 Agent | ReAct模式、四层继承、状态机 |
| Ch10 Web层/SSE | 三种SSE实现、CORS配置 |
| Ch11 前端 | Vue3 + EventSource 流式对话 |
| Ch12 启动实操 | 完整启动步骤与常见问题 |
| 附录 A | 面试高频题 |
| 附录 B | 企业规范对标 |
| 附录 C | 快速速查表 |

---

## 学习路径建议

```
初学者路径：
  Java基础 → Spring Boot (Ch1) → Spring AI核心 (Ch3)
  → 对话记忆 (Ch5) → Function Calling (Ch7) → 启动实操 (Ch12)

进阶路径：
  RAG全链路 (Ch6) → MCP协议 (Ch8) → ReAct Agent (Ch9) → 前端集成 (Ch11)

面试准备：
  附录A (面试题) + 附录C (速查表) + 各章节🏢/💼 标记知识点
```

---

> 📖 **系统学习**：阅读 [read.md](./read.md)（2000+ 行，含完整目录和代码示例）
>
> 💡 **项目来源**：基于鱼皮（yupi）Spring AI 教程实战改编，全链路 AI 应用开发参考实现

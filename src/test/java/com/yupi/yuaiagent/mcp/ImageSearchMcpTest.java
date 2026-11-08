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

import static org.junit.jupiter.api.Assertions.*;

/**
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║              MCP 图片搜索测试 —— 小白完全理解手册                              ║
 * ║         读完这个注释，你能彻底搞懂 MCP 的每一个细节                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 运行前必做：先打包 MCP Server 子进程的 jar 包
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *   cd 08-yu-ai-agent/yu-image-search-mcp-server
 *   mvn package -DskipTests
 *
 *   打包成功后 jar 位置：
 *   yu-image-search-mcp-server/target/yu-image-search-mcp-server-1.0.0.jar
 *
 *   不打包直接运行会报：Error: Unable to access jarfile xxx.jar
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第一章：整个系统有哪些"角色"？——先建立全局地图
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  把整个系统想象成一家公司：
 *
 *    你（老板）
 *      └── 你的 AI 助理（大语言模型，远在云端服务器）
 *            └── 图片搜索外包员工（yu-image-search-mcp-server 子进程）
 *            └── 文件操作外包员工（server-filesystem 子进程）
 *            └── 记忆存储外包员工（server-memory 子进程）
 *            └── 网页抓取外包员工（mcp-fetch 子进程）
 *            └── 浏览器控制外包员工（server-puppeteer 子进程）
 *
 *  你跟 AI 助理说"帮我找几张长靴图片"，AI 助理自己不会搜图，
 *  就把任务派给"图片搜索外包员工"，员工去 Pixabay 搜完后把 URL 交回，
 *  AI 助理整理后告诉你。
 *
 *  这里有两类进程（进程 = 操作系统运行一个程序时分配的独立"房间"）：
 *
 *    父进程（Parent Process）= 启动其他进程的进程
 *      → 就是你运行测试时启动的那个 JVM，PID 81386（你日志里那个数字）
 *      → 里面跑着 Spring Boot 容器、ChatModel Bean、SyncMcpToolCallbackProvider 等
 *
 *    子进程（Child Process）= 被父进程启动的进程
 *      → 就是 5 个 MCP Server，每个都是独立的 JVM 或 Node.js 进程
 *      → 它们有自己独立的内存，父进程不能直接读写它们的变量
 *      → 父子进程之间必须靠"管道"通信（下面详细讲）
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第二章：@SpringBootTest 是什么？它启动时做了什么？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  @SpringBootTest 的作用 = 启动完整的 Spring Boot 应用，然后跑测试。
 *
 *  不加这个注解：测试类只是一个普通 Java 类，@Autowired 字段全是 null，
 *               什么 Bean 都拿不到，运行直接报 NullPointerException。
 *
 *  加了之后，Spring 在测试开始前会依次做：
 *    1. 读 application.yml / application-local.yml 配置文件
 *    2. 扫描所有加了 @Service / @Component / @Bean 的类，创建对象（Bean）
 *    3. 初始化 AI 相关 Bean：ChatModel、VectorStore、SyncMcpToolCallbackProvider 等
 *    4. 读取 mcp-servers.json，启动 5 个 MCP Server 子进程
 *    5. 与每个子进程完成 MCP 握手，把工具注册进 SyncMcpToolCallbackProvider
 *
 *  所以你在测试方法里看到的 toolCallbackProvider 里已经装好了所有工具，
 *  这是 Spring 容器启动时自动做的，不需要你手动写任何代码。
 *
 *  ── Bean 是什么？──
 *
 *  Bean = "被 Spring 容器管理的对象"。
 *  没有 Spring，你创建对象要自己 new：
 *
 *    ImageSearchTool tool = new ImageSearchTool();  // 手动创建，自己管理生命周期
 *
 *  有了 Spring，你只需要贴注解，Spring 帮你创建并管理：
 *
 *    @Service
 *    public class ImageSearchTool { ... }   // 贴了 @Service，Spring 会自动创建它
 *
 *    @Autowired
 *    ImageSearchTool tool;   // 在任何 Spring 管理的类里，用 @Autowired 直接"领取"
 *
 *  好处：对象只创建一次（单例），你不用操心何时 new、何时 close。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第三章：@Autowired 和 @Qualifier 是什么？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  @Autowired = "请 Spring 帮我自动注入一个匹配的 Bean"
 *
 *  @Qualifier("openAiChatModel") = "容器里有多个 ChatModel，我要名字叫这个的那一个"
 *
 *  为什么需要 @Qualifier？
 *    因为 application.yml 里同时配了两个 AI 模型：
 *      spring.ai.openai.*       → Spring AI 自动创建 Bean 名 "openAiChatModel"
 *      spring.ai.dashscope.*    → Spring AI 自动创建 Bean 名 "dashScopeChatModel"
 *
 *    不加 @Qualifier：Spring 不知道给你哪个，直接报错
 *      "No qualifying bean of type 'ChatModel' — expected single matching bean but found 2"
 *
 *    加了 @Qualifier("openAiChatModel")：明确指定用本地 OpenAI 代理（localhost:6655）
 *
 *  代码示例对比：
 *
 *    @Autowired
 *    private ChatModel chatModel;           // ❌ 报错：容器里有2个 ChatModel
 *
 *    @Autowired
 *    @Qualifier("openAiChatModel")
 *    private ChatModel openAiChatModel;     // ✅ 正确：指定用本地 OpenAI 的那个
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第四章：子进程怎么被启动的？——ProcessBuilder 和操作系统的关系
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Spring AI 读到 mcp-servers.json 里的 yu-image-search-mcp-server 配置后，
 *  内部大致等价于执行了这段 Java 代码：
 *
 *    // ProcessBuilder = Java 标准库的类，作用是"用 Java 代码启动外部程序"
 *    // 等价于在终端手动输入那一行命令
 *    ProcessBuilder pb = new ProcessBuilder(
 *        "java",
 *        "-Dspring.ai.mcp.server.stdio=true",   // 告诉子进程：用 stdio 模式运行
 *        "-Dspring.main.web-application-type=none", // 告诉子进程：不要启动 HTTP 服务器
 *        "-Dlogging.pattern.console=",           // 告诉子进程：关掉 stdout 日志输出（见下）
 *        "-jar",
 *        "yu-image-search-mcp-server-1.0.0.jar"
 *    );
 *
 *    Process childProcess = pb.start();  // ← 操作系统在这里创建新的 JVM 进程！
 *
 *  执行 pb.start() 后：
 *    - 操作系统分配一个新的"房间"（进程）给子进程
 *    - 子进程有自己独立的内存，和父进程完全隔离
 *    - 操作系统同时自动创建 3 根"管道"连接父子进程（见下章）
 *
 *  为什么要 -Dlogging.pattern.console= （空字符串）？
 *
 *    子进程是 Spring Boot 程序，默认会把日志输出到 stdout（标准输出）。
 *    但 stdout 是 MCP 协议的专用通道！如果日志掺进来，
 *    父进程收到的数据就不是纯净的 JSON 了，解析会崩溃。
 *
 *    加了这个参数后，Logback（Java 的日志框架）的 console 输出模式变为空，
 *    不再往 stdout 打日志。所以你在父进程日志里会看到子进程报的警告：
 *      StdioClientTransport: STDERR Message received: Empty or null pattern.
 *    这个警告是正常的，证明"关日志"成功了。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第五章：父子进程如何通信？——stdin/stdout 管道详解
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  进程之间内存完全隔离，不能直接共享变量。
 *  它们靠操作系统提供的"管道（Pipe）"来传递数据。
 *
 *  管道 = 操作系统开辟的一块缓冲区（先进先出队列），
 *         一边写入数据，另一边读出数据，像水管一样。
 *
 *  每个进程生来就有 3 根管道（操作系统分配的）：
 *
 *    stdin  （标准输入，Standard Input）
 *           = 进程的"耳朵"，别人写进来，这个进程能读到
 *           = 在终端里就是你键盘输入的内容
 *
 *    stdout （标准输出，Standard Output）
 *           = 进程的"嘴巴"，这个进程往里写，别人能读到
 *           = 在终端里就是 System.out.println() 打印到屏幕的内容
 *
 *    stderr （标准错误，Standard Error）
 *           = 进程的"报错嘴巴"，专门输出错误和警告信息
 *           = 在终端里和 stdout 都显示在屏幕上，但可以单独重定向
 *
 *  父子进程的管道连接方式（ProcessBuilder.start() 自动建立）：
 *
 *    父进程（Spring Boot）               子进程（MCP Server）
 *         │                                    │
 *         │   写入 ──→  [stdin 管道]  ──→  读取  │
 *         │                                    │
 *         │   读取 ←──  [stdout 管道] ←──  写入  │
 *         │                                    │
 *         │   读取 ←──  [stderr 管道] ←──  写入  │
 *
 *  用 Java 代码获取这 3 根管道：
 *
 *    Process child = pb.start();
 *    OutputStream toChildStdin    = child.getOutputStream();  // 父→子：往这里写，子能收到
 *    InputStream  fromChildStdout = child.getInputStream();   // 子→父：子写进去，父在这读
 *    InputStream  fromChildStderr = child.getErrorStream();   // 子的报错，父在这里读
 *
 *  重要规则：
 *    stdout 是 MCP 协议的专用通道，只能传 MCP 的 JSON 数据，绝对不能混入日志！
 *    stderr 可以随便输出，父进程会收集起来打成日志（StdioClientTransport: STDERR Message received）
 *           这就是你在日志里看到子进程打印的各种启动信息的原因。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第六章：MCP 协议是什么？——管道里传的是什么格式的数据？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  有了管道（物理连接），还需要规定"说什么格式的话"，双方才能理解彼此。
 *  MCP（Model Context Protocol，模型上下文协议）就是这套规范。
 *
 *  类比：
 *    管道 = 电话线（物理能传声音）
 *    MCP  = 普通话（双方说同一种语言，才能理解意思）
 *    有了电话线（管道）+ 说普通话（MCP协议），才能真正沟通。
 *
 *  MCP 底层使用 JSON-RPC 2.0 格式：
 *
 *  JSON-RPC 是什么？
 *    JSON-RPC = JSON 格式的"远程打电话"协议（RPC = Remote Procedure Call，远程过程调用）
 *    就是用 JSON 发送"请帮我调用某个函数，参数是这些"，对方返回"结果是这个"
 *
 *  JSON-RPC 消息的固定格式：
 *
 *    请求格式（父进程发给子进程）：
 *    {
 *      "jsonrpc": "2.0",        // 版本号，固定写 "2.0"
 *      "id": 1,                 // 请求编号（用来匹配哪条请求对应哪条响应）
 *      "method": "方法名",      // 要调用什么操作
 *      "params": { 参数 }       // 调用的参数
 *    }
 *
 *    响应格式（子进程回给父进程）：
 *    {
 *      "jsonrpc": "2.0",
 *      "id": 1,                 // 和请求的 id 对应，表示这是哪个请求的回复
 *      "result": { 返回值 }     // 正常结果
 *    }
 *    或者出错时：
 *    {
 *      "jsonrpc": "2.0",
 *      "id": 1,
 *      "error": { "code": -1, "message": "出错原因" }
 *    }
 *
 *  MCP 里常用的 method（操作类型）：
 *    "initialize"   → 握手第一步：互相介绍，确认协议版本
 *    "tools/list"   → 询问子进程有哪些工具
 *    "tools/call"   → 调用子进程的某个工具，传入参数，拿回结果
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第七章：MCP 握手全过程——父子进程怎么建立联系？（3步）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  子进程启动后，管道已经连好，但双方还不知道对方是谁、有什么能力。
 *  握手就是"互相介绍 + 问你会什么"的过程，共 3 步。
 *
 *  ── 第1步：initialize（互相介绍，确认说同一种语言）──
 *
 *  父进程通过 stdin 管道发送（每行一个 JSON，换行符分隔消息）：
 *
 *    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
 *      "protocolVersion":"2024-11-05",
 *      "capabilities":{},
 *      "clientInfo":{"name":"spring-ai-mcp-client","version":"1.0.0"}
 *    }}
 *
 *  子进程从 stdin 读到这条消息，通过 stdout 回复：
 *
 *    {"jsonrpc":"2.0","id":1,"result":{
 *      "protocolVersion":"2024-11-05",
 *      "capabilities":{"tools":{"listChanged":true}},
 *      "serverInfo":{"name":"yu-image-search-mcp-server","version":"0.0.1"}
 *    }}
 *
 *  capabilities（能力声明）是什么？
 *    子进程告诉父进程"我有什么能力"的清单。
 *    "tools":{"listChanged":true} 表示：我有工具能力，且工具列表可能动态变化。
 *    父进程看到这个，就知道下一步要去问工具列表。
 *
 *  这步对应日志里的这行：
 *    LifecycleInitializer: Server response with Protocol: 2024-11-05,
 *      Capabilities: ServerCapabilities[tools=ToolCapabilities[listChanged=true]],
 *      Info: Implementation[name=yu-image-search-mcp-server, version=0.0.1]
 *
 *  ── 第2步：tools/list（问你有哪些工具）──
 *
 *  父进程通过 stdin 发送：
 *
 *    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
 *
 *  子进程通过 stdout 回复（把 @Tool 注解里的信息序列化成 JSON）：
 *
 *    {"jsonrpc":"2.0","id":2,"result":{
 *      "tools":[{
 *        "name":"searchImage",
 *        "description":"Search images from Pixabay by keyword, returns medium-size image URLs",
 *        "inputSchema":{
 *          "type":"object",
 *          "properties":{
 *            "query":{
 *              "type":"string",
 *              "description":"Search query keyword, e.g. 'cat', 'mountain', 'computer'"
 *            }
 *          },
 *          "required":["query"]
 *        }
 *      }]
 *    }}
 *
 *  inputSchema 是什么？
 *    JSON Schema 格式，描述这个工具接受的参数结构：
 *    哪些参数必填（required）、每个参数是什么类型（type）、什么含义（description）。
 *    AI 模型靠这个 schema 来判断"调用这个工具时我该传什么参数"。
 *
 *  Spring AI 收到工具列表后，把每个工具包装成 ToolCallback 对象：
 *
 *    // Spring AI 内部简化逻辑：
 *    for (Tool tool : toolsListResult.tools()) {
 *        ToolCallback cb = new McpToolCallback(mcpClient, tool);
 *        toolCallbackProvider.register(cb);
 *    }
 *    // 此后 toolCallbackProvider.getToolCallbacks() 就能拿到 searchImage 工具
 *
 *  ── 第3步：握手完成，持续保持管道连接 ──
 *
 *  握手结束后，子进程不退出，一直在后台运行，监听 stdin，随时等待下一条指令。
 *  管道始终保持连通，就像一条"专线电话"一直没挂断。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第八章：Function Calling（AI 如何决定调用哪个工具）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Function Calling 是大语言模型的一种能力：
 *  当 AI 发现用户的需求匹配某个工具时，它不直接回答文字，
 *  而是返回一个"我要调用这个工具"的指令，让框架去真正执行工具。
 *
 *  整个过程分两轮 HTTP 调用（用户只感知到一次问答）：
 *
 *  ── 第一轮：用户问题 → AI 决定调工具 ──
 *
 *  发送给 AI（内容 = 用户消息 + 所有工具的说明书）：
 *
 *    messages: [
 *      {"role":"system", "content":"你是恋爱专家..."},
 *      {"role":"user",   "content":"帮我找几张穿长靴的图片，给我图片链接"}
 *    ]
 *    tools: [
 *      {
 *        "name": "searchImage",
 *        "description": "Search images from Pixabay by keyword...",
 *        "parameters": { "query": {"type":"string","description":"Search query..."} }
 *      },
 *      // ... 其他几十个工具的说明书
 *    ]
 *
 *  AI 在云端思考（这个过程你看不到）：
 *    "用户要找图片 → 我有 searchImage 工具 → 工具描述说能搜 Pixabay 图片
 *     → 完全匹配！→ 调用它，把'长靴'翻译成英文关键词 'boots'"
 *
 *  AI 的第一轮回复（不是文字，是调用指令）：
 *
 *    {
 *      "role": "assistant",
 *      "content": null,              // 没有文字回复
 *      "tool_calls": [{              // 而是工具调用指令
 *        "id": "call_abc123",
 *        "type": "function",
 *        "function": {
 *          "name": "searchImage",
 *          "arguments": "{\"query\": \"boots\"}"
 *        }
 *      }]
 *    }
 *
 *  tool_calls 是什么？
 *    大语言模型返回的一种特殊输出格式，表示"我决定调用这个工具，参数是这个"。
 *    注意：AI 本身不执行工具！AI 只是输出"指令"，真正执行工具的是 Spring AI 框架。
 *    就像公司老板发指令，但具体干活的是员工。
 *
 *  ── Spring AI 收到 tool_calls 后，自动执行工具（通过管道） ──
 *
 *  Spring AI 框架找到 searchImage 工具对应的 ToolCallback，调用它：
 *    → 通过 stdin 管道发给 yu-image-search-mcp-server 子进程：
 *      {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
 *        "name":"searchImage",
 *        "arguments":{"query":"boots"}
 *      }}
 *
 *    → 子进程 ImageSearchTool.searchImage("boots") 执行：
 *        调用 Pixabay REST API
 *        拿到 20 张图片数据
 *        提取每张图片的 webformatURL（640px 版本链接）
 *        返回逗号分隔的 URL 字符串
 *
 *    → 子进程通过 stdout 管道返回结果：
 *      {"jsonrpc":"2.0","id":3,"result":{
 *        "content":[{"type":"text","text":"https://pixabay.com/get/url1.jpg,url2.jpg,..."}]
 *      }}
 *
 *  ── 第二轮：工具结果 → AI 整理成自然语言 ──
 *
 *  Spring AI 把工具结果追加到对话历史，再次调用 AI：
 *
 *    messages: [
 *      {"role":"system",    "content":"你是恋爱专家..."},
 *      {"role":"user",      "content":"帮我找几张穿长靴的图片"},
 *      {"role":"assistant", "content":null, "tool_calls":[...]},  // 上一轮 AI 的指令
 *      {"role":"tool",      "content":"https://pixabay.com/get/url1.jpg,url2.jpg,...",
 *       "tool_call_id":"call_abc123"}  // 工具的返回结果
 *    ]
 *
 *  AI 第二轮回复（这才是给用户看的）：
 *    "给你 6 张穿长靴的图片（来自 Pixabay），点击即可查看：
 *     1. [长靴图片1](https://pixabay.com/get/url1.jpg)
 *     ..."
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第九章：@Tool 和 @ToolParam 注解——工具是怎么定义的？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  在 ImageSearchTool.java 里：
 *
 *    @Tool(description = "Search images from Pixabay by keyword, returns medium-size image URLs")
 *    public String searchImage(
 *        @ToolParam(description = "Search query keyword, e.g. 'cat', 'mountain'") String query
 *    ) { ... }
 *
 *  @Tool = 把这个 Java 方法变成"AI 可以调用的工具"，description 是给 AI 看的说明书。
 *  AI 靠 description 的文字来判断：遇到什么样的用户需求时应该调用这个工具。
 *  所以 description 写得越清楚，AI 就越会在合适的时候调用。
 *
 *  @ToolParam = 给参数加说明，告诉 AI 这个参数填什么。
 *  AI 会根据用户的话自动提取参数值：
 *    用户说"帮我找几张山的图片"
 *    AI 读 @ToolParam 的 description → 知道 query 要填英文搜索词 → 自动填入 "mountain"
 *
 *  用户说中文，AI 为什么能填英文参数？
 *    因为大语言模型本身就懂多语言，会自动把"长靴"翻译成 "boots"。
 *    这是 AI 的语言理解能力，不是代码逻辑。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第十章：为什么图片链接在日志里出现好几遍？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  很多人看到日志里图片链接出现好几遍，以为 AI 调用了好几次工具。
 *  实际上：AI 只调用了 1 次 searchImage，Pixabay 只被请求了 1 次。
 *  同一份结果被 4 个地方分别打印了出来：
 *
 *  MyLoggerAdvisor 是什么？
 *    一种拦截器（Advisor），在每次 AI 调用前后自动执行，用来打印日志。
 *    类比：快递员每次送货，进门刷卡（before），出门刷卡（after），快递内容不变。
 *
 *  4 次打印的来源：
 *
 *    第1遍：MyLoggerAdvisor: AI 响应: 给你6张穿长靴的图片...
 *           来自 MyLoggerAdvisor 的 after 回调，Function Calling 第2轮 AI 调用结束
 *
 *    第2遍：MyLoggerAdvisor: AI 响应: 给你6张穿长靴的图片...（内容完全相同）
 *           Advisor 在 doChatWithMcp 里被注册了2次（buildChatClient 一次，方法内一次），
 *           所以同一次 AI 调用被记录了2次
 *
 *    第3遍：[MCP] chatId=... | ai=给你6张穿长靴的图片...
 *           来自 LoveApp.doChatWithMcp() 末尾的 log.info 业务日志
 *
 *    第4遍：═══ AI 回答 ═══ 之后那段
 *           来自测试代码的 System.out.println(answer)，测试代码主动打印结果
 *
 *  结论：4遍 = 1份结果被4个地方打印，不是4次工具调用，不是4次 AI 请求。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第十一章：进程退出时发生了什么？——exit code 143 的含义
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  测试结束时，Spring Boot 关闭，父进程向 5 个子进程发 SIGTERM 信号。
 *  SIGTERM（信号15）= "请你礼貌地退出"，子进程收到后正常关闭。
 *  退出码 143 = 128 + 15 = 被 SIGTERM 终止，这是完全正常的。
 *
 *  你在日志里看到：
 *    Process terminated with code 143   → 子进程正常退出，不是报错
 *    MCP server process stopped         → Spring AI 确认子进程已停止
 *    Process finished with exit code 0  → 父进程（整个测试）正常结束，0 = 成功
 *
 *  exit code 规则：
 *    0   = 成功（万事大吉）
 *    非0  = 有错误发生
 *    143  = 被 SIGTERM 信号终止（= 正常关闭，不是错误）
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 第十二章：完整调用链路总图——从测试启动到 exit code 0
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  【启动阶段】@SpringBootTest 触发，Spring 容器初始化
 *
 *    Spring 读 mcp-servers.json
 *      │ 发现5个 MCP Server 配置
 *      │
 *      ├─ ProcessBuilder.start("java -jar yu-image-search-mcp-server.jar")
 *      │    ↓ 子进程1启动，3根管道连好
 *      │    ↓ stdin管道发 initialize → stdout管道收到握手响应
 *      │    ↓ stdin管道发 tools/list → stdout管道收到 [searchImage 工具描述]
 *      │    ↓ searchImage 封装成 ToolCallback → 存入 SyncMcpToolCallbackProvider
 *      │
 *      ├─ ProcessBuilder.start("npx server-filesystem")    子进程2，同上
 *      ├─ ProcessBuilder.start("npx server-memory")        子进程3，同上
 *      ├─ ProcessBuilder.start("npx mcp-fetch")            子进程4，同上
 *      └─ ProcessBuilder.start("npx server-puppeteer")     子进程5，同上
 *
 *  【测试执行阶段】testSearchBootsImage() 被调用
 *
 *    loveApp.doChatWithMcp("帮我找几张穿长靴的图片", chatId)
 *      │
 *      ↓ MyLoggerAdvisor 打印"请求消息"
 *      ↓ 发 HTTP 请求给 AI（消息 + 几十个工具描述），第1轮
 *      │
 *      ↓ AI 响应：tool_calls: [{name:"searchImage", arguments:{query:"boots"}}]
 *      │
 *      ↓ Spring AI 找到 searchImage 的 ToolCallback
 *      ↓ 通过 stdin 管道发 tools/call 给子进程1
 *      ↓ 子进程1 调用 Pixabay API，拿到 20 个图片 URL
 *      ↓ 子进程1 通过 stdout 管道返回 URL 列表
 *      │
 *      ↓ 发 HTTP 请求给 AI（带工具结果），第2轮
 *      ↓ AI 响应：整理成 Markdown 格式的图片链接文字
 *      │
 *      ↓ MyLoggerAdvisor 打印 AI 响应（图片链接，第1遍）
 *      ↓ MyLoggerAdvisor 再次打印（第2遍，注册了2次）
 *      ↓ LoveApp.log.info 打印（第3遍）
 *      ↓ doChatWithMcp() 返回 answer 字符串
 *      │
 *    System.out.println(answer)（第4遍）
 *    assertTrue 断言通过
 *
 *  【结束阶段】
 *
 *    Spring 关闭 → 向5个子进程发 SIGTERM → 子进程退出(code 143) → 父进程 exit code 0
 */
@SpringBootTest
public class ImageSearchMcpTest {

    // 本地 OpenAI 兼容模型（通过本地代理 http://localhost:6655/openai 访问）
    // 用 @Qualifier 指定 Bean 名，因为容器里同时存在 openAiChatModel 和 dashScopeChatModel
    // Bean 名 "openAiChatModel" 由 Spring AI 根据 spring.ai.openai 配置自动注册
    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel openAiChatModel;

    // MCP 工具提供者：Spring 启动时自动读取 mcp-servers.json，
    // 连接所有 MCP Server，把它们的工具封装在这个 Bean 里
    @Autowired
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    /**
     * 测试一：验证 searchImage 工具是否已成功注册
     *
     * 目的：只验证"工具发现"阶段——确认 MCP Server 子进程已正常启动，
     *       工具已通过 MCP 握手协议注册进来。
     *
     * 如果这个测试失败：
     *   - 检查 jar 是否存在（先 mvn package）
     *   - 检查 mcp-servers.json 里的 jar 路径是否正确
     *   - 检查启动日志里有没有 "MCP server started"
     */
    @Test
    void testToolRegistered() {
        ToolCallback[] tools = toolCallbackProvider.getToolCallbacks();

        // 打印所有已注册的 MCP 工具（方便排查）
        System.out.println("═══ 已注册的 MCP 工具（共 " + tools.length + " 个）═══");
        Arrays.stream(tools).forEach(t ->
                System.out.printf("  %-40s %s%n",
                        t.getToolDefinition().name(),
                        t.getToolDefinition().description())
        );

        // 断言：searchImage 工具必须在列表里
        boolean hasSearchImage = Arrays.stream(tools)
                .anyMatch(t -> t.getToolDefinition().name().equals("searchImage"));

        assertTrue(hasSearchImage,
                "searchImage 工具未注册！请检查：\n" +
                "1. yu-image-search-mcp-server 是否已执行 mvn package\n" +
                "2. mcp-servers.json 里的 jar 路径：yu-image-search-mcp-server/target/yu-image-search-mcp-server-1.0.0.jar\n" +
                "3. 启动日志是否出现 MCP server started");
    }

    /**
     * 测试二：端到端——让 AI 搜索"穿长靴的图片"
     *
     * 完整链路：
     *   用户中文输入 → AI 理解意图 → AI 提取英文关键词 → 调用 searchImage(query="boots")
     *   → Pixabay API 返回图片 URL → AI 汇总成自然语言回复
     *
     * 预期输出：AI 回复里包含 pixabay.com 的图片 URL（webformat 格式，640px）
     */
    @Test
    void testSearchBootsImage() {
        LoveApp loveApp = new LoveApp(openAiChatModel);
        loveApp.setToolCallbackProvider(toolCallbackProvider);
        String chatId = UUID.randomUUID().toString();

        // 用自然语言提问，AI 自动识别关键词并调用工具
        String answer = loveApp.doChatWithMcp(
                "帮我找几张mcp知识的图片，给我图片链接",
                chatId
        );

        System.out.println("═══ AI 回答 ═══");
        System.out.println(answer);

        assertNotNull(answer, "AI 回复不应为 null");
        assertTrue(answer.length() > 10, "AI 回复内容太短，可能工具调用失败");
        // Pixabay 返回的 URL 都包含 pixabay.com 域名
        assertTrue(answer.contains("pixabay.com") || answer.contains("http"),
                "回复里没有图片链接，AI 可能没有调用 searchImage 工具");
    }
}

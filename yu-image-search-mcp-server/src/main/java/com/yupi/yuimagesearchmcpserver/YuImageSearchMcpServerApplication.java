package com.yupi.yuimagesearchmcpserver;

import com.yupi.yuimagesearchmcpserver.tools.ImageSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║         yu-image-search-mcp-server 启动入口 —— 小白完全理解手册            ║
 * ║  读完这个注释，你能搞懂：这个项目是什么、父子进程怎么回事、调用全流程        ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 一、这个项目是什么？在整个系统里扮演什么角色？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  这是一个独立的 Spring Boot 程序，专门作为 MCP Server（工具提供方）运行。
 *  它不是 yu-ai-agent 的一部分，而是一个被 yu-ai-agent 当作"子进程"调用的独立 jar。
 *
 *  整个系统的角色分工（公司类比）：
 *
 *    你（用户）
 *      ↓  说"帮我找几张长靴图片"
 *    yu-ai-agent（MCP Client = 公司总部 + AI 助理）
 *      ↓  AI 决定需要搜图，通过管道派任务
 *    yu-image-search-mcp-server（MCP Server = 图片搜索外包公司）← 你现在看的
 *      ↓  调用 Pixabay REST API
 *    Pixabay 图片网站（数据来源）
 *
 *  为什么要单独做一个项目，不直接写在 yu-ai-agent 里？
 *  → 单一职责原则：一个模块只干一件事
 *  → 解耦：图片搜索逻辑改了，只重新打包本项目的 jar，yu-ai-agent 不用动
 *  → 复用：任何 AI Agent（不只是 yu-ai-agent）都可以通过 MCP 协议使用本工具
 *  → 语言无关：MCP 协议是通用的，工具可以用 Java/Python/Node.js 任何语言写
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 二、父进程和子进程是什么？怎么建立联系？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  进程（Process）= 操作系统运行一个程序时分配的独立"房间"。
 *    每个进程有自己独立的内存，进程之间不能直接访问对方的变量。
 *    就像两个不同的公司，各有各的办公室，不能直接走进对方的房间拿东西。
 *
 *  父进程（Parent Process）= 启动其他程序的进程
 *    → yu-ai-agent 运行时的 JVM（Java 虚拟机）进程
 *    → 你在 IDEA 点运行测试，操作系统分配一个进程，PID（进程ID）如 81386
 *
 *  子进程（Child Process）= 被父进程启动的进程
 *    → 本项目（yu-image-search-mcp-server）运行时的 JVM 进程
 *    → 父进程用 Java 的 ProcessBuilder 类启动它（等价于在终端手敲命令）
 *
 *  父进程怎么启动子进程？（Spring AI 内部等价逻辑）
 *
 *    // ProcessBuilder = Java 标准库的类，在代码里启动外部程序
 *    // 等价于在终端手动输入：
 *    //   java -Dspring.ai.mcp.server.stdio=true -jar yu-image-search-mcp-server.jar
 *    ProcessBuilder pb = new ProcessBuilder(
 *        "java",
 *        "-Dspring.ai.mcp.server.stdio=true",
 *        "-Dspring.main.web-application-type=none",
 *        "-Dlogging.pattern.console=",
 *        "-jar",
 *        "yu-image-search-mcp-server-1.0.0.jar"
 *    );
 *    Process childProcess = pb.start();  // ← 操作系统在这里真正创建子进程
 *
 *  父子进程内存隔离，靠"管道（Pipe）"通信：
 *
 *    管道 = 操作系统提供的先进先出数据通道，像水管，一端写入，另一端读出。
 *    每个进程天生有 3 根管道：
 *
 *      stdin  （标准输入）= 进程的"耳朵"，别人写进来，这个进程能读到
 *      stdout （标准输出）= 进程的"嘴巴"，这个进程写出去，别人能读到
 *      stderr （标准错误）= 专门输出错误和警告，不影响正常数据通道
 *
 *    父子进程管道连接示意图（pb.start() 后操作系统自动建立）：
 *
 *      父进程（yu-ai-agent）              子进程（本项目）
 *            │                                │
 *            │  写入 ──▶ [stdin 管道]  ──▶ 读取│  ← 父发指令给子
 *            │  读取 ◀── [stdout 管道] ◀── 写入│  ← 子返回结果给父
 *            │  读取 ◀── [stderr 管道] ◀── 写入│  ← 子的报错/日志
 *
 *  重要规则：
 *    stdout 是 MCP 协议的专用数据通道，只能传 JSON 格式的协议消息！
 *    如果日志、Banner、任何多余文字混进 stdout，父进程解析 JSON 会直接崩溃。
 *    这就是为什么启动参数里要加 -Dlogging.pattern.console=（关日志到stdout）
 *    和配置 banner-mode: off（关启动图案到stdout）。
 *    stderr 可以随便输出，父进程会收集并打成日志，不影响协议通道。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 三、启动命令里每个 JVM 参数是什么意思？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  完整启动命令（来自 mcp-servers.json 的配置）：
 *    java
 *      -Dspring.ai.mcp.server.stdio=true
 *      -Dspring.main.web-application-type=none
 *      -Dlogging.pattern.console=
 *      -jar
 *      yu-image-search-mcp-server-1.0.0.jar
 *
 *  逐个参数解释：
 *
 *  java
 *    启动 JVM（Java Virtual Machine，Java 虚拟机）的命令。
 *    JVM 是运行 Java 字节码的虚拟机，每执行一次 java 命令，
 *    操作系统就创建一个新的 JVM 进程（独立的内存空间）。
 *    父进程是一个 JVM，本子进程也是一个 JVM，两个完全独立。
 *
 *  -D（Define，定义）
 *    -D 开头的参数叫 JVM 系统属性（System Property）。
 *    作用：在 JVM 启动时设置一个全局变量，程序里可以用 System.getProperty() 读取。
 *    等价于程序启动前先执行了：
 *      System.setProperty("spring.ai.mcp.server.stdio", "true");
 *    Spring Boot 启动时会读这些系统属性，用来覆盖或补充 application.yml 的配置。
 *
 *  -Dspring.ai.mcp.server.stdio=true
 *    告诉 Spring AI MCP Server 框架：用 stdio 模式运行。
 *    框架收到这个配置后，启动一个"stdin 监听器"：
 *      不断从 stdin 读取数据 → 解析 JSON-RPC 消息 → 处理 → 通过 stdout 回复。
 *    同时这个参数让 Spring Boot 激活 "stdio" Profile，
 *    加载 application-stdio.yml（详见 application.yml 的注释）。
 *
 *  -Dspring.main.web-application-type=none
 *    告诉 Spring Boot：不要启动任何 Web 服务器（Tomcat/Netty）。
 *    Spring Boot 默认启动时会自动拉起 Tomcat，监听 8080 端口等 HTTP 请求。
 *    但 stdio 模式完全通过管道通信，根本用不到 HTTP 端口。
 *    不加这个参数，Tomcat 白白占用内存和端口，还可能和其他服务冲突。
 *
 *  -Dlogging.pattern.console=（等号后面是空字符串，什么都没有）
 *    告诉 Logback（Java 的日志框架）：stdout 的日志输出模式为空。
 *    效果：Logback 发现模式是空，报一个内部警告后不再往 stdout 输出任何日志。
 *    你会在父进程日志里看到子进程通过 stderr 传回的警告：
 *      "StdioClientTransport: STDERR Message received: Empty or null pattern."
 *    这个警告是正常的，证明关日志成功了，不用管它。
 *    为什么不用 -Dlogging.level.root=OFF？因为那会连 stderr 日志也关掉，
 *    调试时完全看不到子进程的报错，很难排查问题。
 *
 *  -jar xxx.jar
 *    运行这个 jar 包。这个 jar 是"fat-jar"（胖jar），
 *    即通过 spring-boot-maven-plugin 打包的，包含所有依赖，可独立运行。
 *    普通 mvn package 打出的 jar 不含依赖，java -jar 会报 ClassNotFoundException。
 *    fat-jar 把所有依赖 jar 都塞进去，一个文件就能独立运行。
 *    运行前必须先执行：cd yu-image-search-mcp-server && mvn package -DskipTests
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 四、MCP 握手全过程——父子进程怎么建立协议联系？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  管道连好后，双方还不知道对方是谁、有什么能力，需要"握手"：
 *
 *  MCP 协议基于 JSON-RPC 2.0 格式（每条消息是一行 JSON，换行符分隔消息）：
 *    JSON-RPC = JSON 格式的远程调用协议（RPC = Remote Procedure Call，远程过程调用）
 *    请求格式：{"jsonrpc":"2.0","id":编号,"method":"操作名","params":{参数}}
 *    响应格式：{"jsonrpc":"2.0","id":编号,"result":{返回值}}
 *    id 字段：用来匹配"哪条响应对应哪条请求"，像快递单号
 *
 *  ── 握手第1步：initialize（双方互相介绍） ──
 *
 *  父进程通过 stdin 管道写入：
 *    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
 *      "protocolVersion":"2024-11-05",
 *      "clientInfo":{"name":"spring-ai-mcp-client","version":"1.0.0"}
 *    }}
 *
 *  子进程（本项目）从 stdin 读到这条消息，通过 stdout 管道写回：
 *    {"jsonrpc":"2.0","id":1,"result":{
 *      "protocolVersion":"2024-11-05",
 *      "capabilities":{"tools":{"listChanged":true}},
 *      "serverInfo":{"name":"yu-image-search-mcp-server","version":"0.0.1"}
 *    }}
 *
 *    capabilities = 能力清单，告诉父进程"我有工具能力"
 *    serverInfo   = 自我介绍，名字和版本来自 application-stdio.yml 的配置
 *
 *  对应父进程日志：
 *    LifecycleInitializer: Server response with Protocol: 2024-11-05,
 *      Capabilities: ServerCapabilities[tools=ToolCapabilities[listChanged=true]],
 *      Info: Implementation[name=yu-image-search-mcp-server, version=0.0.1]
 *
 *  ── 握手第2步：tools/list（父进程询问工具清单） ──
 *
 *  父进程通过 stdin 写入：
 *    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
 *
 *  子进程通过 stdout 写回（这段 JSON 是框架自动从 @Tool 注解生成的）：
 *    {"jsonrpc":"2.0","id":2,"result":{
 *      "tools":[{
 *        "name":"searchImage",
 *        "description":"Search images from Pixabay by keyword...",
 *        "inputSchema":{
 *          "type":"object",
 *          "properties":{
 *            "query":{"type":"string","description":"Search query keyword, e.g. 'cat'"}
 *          },
 *          "required":["query"]
 *        }
 *      }]
 *    }}
 *
 *    inputSchema = JSON Schema 格式，描述工具参数结构。
 *    AI 模型靠这个 schema 知道"调用这个工具时该传什么参数、参数是什么类型"。
 *
 *  父进程收到后：把 searchImage 包装成 ToolCallback 存进 SyncMcpToolCallbackProvider。
 *
 *  ── 握手第3步：保持连接，等待调用 ──
 *
 *  握手完成后子进程不退出，一直监听 stdin，像一个始终在线的客服：
 *    父进程随时通过 stdin 发 tools/call 消息，子进程处理后通过 stdout 回复结果。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 五、工具被调用时——tools/call 全流程
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  用户说"帮我找几张穿长靴的图片" → AI 决定调用 searchImage 工具
 *
 *  父进程通过 stdin 写入：
 *    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
 *      "name":"searchImage",
 *      "arguments":{"query":"boots"}
 *    }}
 *
 *  子进程收到后：
 *    MCP Server 框架读到 name="searchImage"，找到对应的 ToolCallback
 *    ToolCallback 调用 ImageSearchTool.searchImage("boots")
 *    → ImageSearchTool 调用 Pixabay API：GET https://pixabay.com/api/?key=xxx&q=boots
 *    → Pixabay 返回 JSON，解析出最多 20 个 webformatURL（640px 图片链接）
 *    → 拼成逗号分隔的字符串
 *
 *  子进程通过 stdout 写回：
 *    {"jsonrpc":"2.0","id":3,"result":{
 *      "content":[{
 *        "type":"text",
 *        "text":"https://pixabay.com/get/url1.jpg,url2.jpg,..."
 *      }]
 *    }}
 *
 *  父进程收到结果后：把 URL 列表发给 AI 做第2轮对话，AI 整理成图片链接文字回复用户。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 六、@Tool vs Function Calling，它们是什么关系？
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Function Calling（函数调用）= AI 模型的一种能力：
 *    当用户需求匹配某个工具时，AI 不直接回答文字，
 *    而是输出"我要调用这个工具，参数是这些"的指令，
 *    由框架真正去执行工具，把结果再交给 AI 整理成回答。
 *
 *  @Tool 注解 = 定义工具的 Java 写法（Spring AI 提供）
 *
 *  MCP = 让工具可以被远程调用的传输协议
 *
 *  三者关系：
 *  ┌────────────────────────────────────────────────────────┐
 *  │  Function Calling（同一 JVM 进程内调用工具）              │
 *  │    @Tool 定义工具 → .tools(obj) 注入 → AI 决定调 → 同进程执行 │
 *  ├────────────────────────────────────────────────────────┤
 *  │  MCP（跨进程 / 跨网络调用工具）                           │
 *  │    @Tool 定义工具 → 子进程暴露 → MCP 协议传输 → 跨进程执行 │
 *  └────────────────────────────────────────────────────────┘
 *
 *  工具的定义方式（@Tool）完全一样，区别只是执行在哪里：
 *    本地工具（Function Calling）= 同一个 JVM 里，方法直接调用
 *    MCP 工具                    = 另一个进程里，通过管道的 JSON-RPC 调用
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 七、完整调用链路总图（从启动到返回图片链接）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  【启动阶段】
 *
 *  父进程读 mcp-servers.json → 找到本项目配置
 *    ↓
 *  ProcessBuilder.start("java -D... -jar yu-image-search-mcp-server.jar")
 *    ↓  操作系统创建子进程，3根管道自动建好
 *  子进程 Spring Boot 启动
 *    ↓  读 application.yml → 激活 stdio Profile → 加载 application-stdio.yml
 *    ↓  web-application-type=none：不启 Tomcat
 *    ↓  banner-mode=off：不打启动图案到 stdout
 *    ↓  MCP Server 框架启动，开始监听 stdin
 *    ↓  扫描 @Service，发现 ImageSearchTool → 创建 Bean
 *    ↓  扫描 @Tool，发现 searchImage → 生成工具描述 JSON → 注册工具
 *  握手：父发 initialize → 子回能力清单
 *    ↓
 *  握手：父发 tools/list → 子回 [searchImage 工具描述]
 *    ↓
 *  searchImage 进入 SyncMcpToolCallbackProvider，启动完成
 *
 *  【调用阶段】
 *
 *  用户："帮我找几张穿长靴的图片"
 *    ↓
 *  父进程把消息 + 工具描述一起发给 AI（HTTP 请求，第1轮）
 *    ↓
 *  AI 分析：我有 searchImage 工具，描述说能搜图，匹配！
 *  AI 回复：tool_calls:[{name:"searchImage", arguments:{query:"boots"}}]
 *    ↓
 *  Spring AI 框架找到 searchImage 的 ToolCallback，执行它
 *  父进程 stdin 管道发：{"method":"tools/call","params":{name:"searchImage",arguments:{query:"boots"}}}
 *    ↓
 *  子进程收到 → ImageSearchTool.searchImage("boots") 执行
 *  → Pixabay API 返回图片数据 → 提取 URL → 拼接字符串
 *  子进程 stdout 管道返回：{"result":{"content":[{"text":"url1,url2,..."}]}}
 *    ↓
 *  父进程拿到 URL，加入对话历史，再次发给 AI（HTTP 请求，第2轮）
 *    ↓
 *  AI 整理 URL，生成 Markdown 格式图片链接文字
 *    ↓
 *  用户看到："给你6张穿长靴的图片：1.[长靴图1](url1) ..."
 *
 *  【结束阶段】
 *
 *  测试结束，Spring Boot 关闭 → 向子进程发 SIGTERM 信号（"请礼貌退出"）
 *  子进程收到信号，优雅关闭，退出码 143（= 128 + 15，SIGTERM 信号编号是15）
 *  父进程 exit code 0（测试成功）
 */
@SpringBootApplication
public class YuImageSearchMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuImageSearchMcpServerApplication.class, args);
    }

    /**
     * 把 ImageSearchTool 注册成 MCP 可以发现的工具
     *
     * 为什么需要这个 Bean？
     *   光有 @Tool 注解还不够——那只是在方法上贴了个"我是工具"的标签。
     *   还需要这个 Bean 告诉 Spring AI MCP Server：
     *   "去扫描 imageSearchTool 这个对象，把它所有带 @Tool 的方法都暴露出去"
     *
     * 工作流程：
     *   1. Spring 启动，创建 ImageSearchTool 实例（因为它有 @Service）
     *   2. 这个 Bean 方法被调用，把 ImageSearchTool 实例传进来
     *   3. MethodToolCallbackProvider 扫描该实例的所有 @Tool 方法
     *   4. 生成工具的"说明书"（名称、描述、参数类型）
     *   5. MCP Server 框架读取这份说明书，通过网络暴露给外部调用
     *
     * 类比：
     *   ImageSearchTool 是一个厨师，会做"搜索图片"这道菜（@Tool方法）
     *   这个 Bean 是餐厅前台，负责把菜单（工具列表）挂出去让客人点
     *   MCP Client（yu-ai-agent）是客人，看到菜单后点菜（调用工具）
     *
     * @param imageSearchTool Spring 自动注入的工具实例（因为 @Service 已经是 Bean）
     * @return ToolCallbackProvider 工具回调提供者，MCP框架会读取它
     */
    @Bean
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool)  // 扫描这个对象里所有 @Tool 方法
                .build();
    }
}

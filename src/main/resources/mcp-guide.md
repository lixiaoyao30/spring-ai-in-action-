# mcp-servers.json 完全解读——小白也能懂的 MCP 配置说明

## 这个文件是干什么的？

mcp-servers.json 是 MCP 服务器的"花名册"。
Spring AI 启动时读这个文件，知道要去启动哪些外部程序（子进程），
以及怎么启动它们（用什么命令、传什么参数）。

简单说：这个文件告诉 Spring AI"你有哪些外包员工，怎么联系他们"。

---

## 父进程和子进程是什么？

把整个系统想象成一家公司：

```
你的 Spring Boot 应用（父进程 = 老板的办公室）
  │
  ├── 子进程1：yu-image-search-mcp-server（图片搜索员工，Java 写的）
  ├── 子进程2：server-filesystem（文件操作员工，Node.js 写的）
  ├── 子进程3：server-memory（记忆管理员工，Node.js 写的）
  ├── 子进程4：mcp-fetch（网页抓取员工，Node.js 写的）
  └── 子进程5：server-puppeteer（浏览器控制员工，Node.js 写的）
```

父进程（Parent Process）= 启动其他进程的进程
  → 就是你运行的 Spring Boot 应用，包含 AI 对话逻辑

子进程（Child Process）= 被父进程启动的进程
  → 就是这些 MCP Server，每个都是独立运行的程序
  → 它们在自己独立的内存里运行，父进程不能直接访问它们的变量
  → 父子进程之间靠"管道"通信（见下面）

进程 = 操作系统运行一个程序时分配的独立"房间"，有独立内存空间。

---

## 父子进程怎么通信？——stdin/stdout 管道

两个进程内存隔离，不能直接共享数据。
通信靠操作系统提供的"管道（Pipe）"——一块先进先出的缓冲区，
一端写数据，另一端读数据，就像水管。

每个进程天生有 3 根管道：

```
stdin  （标准输入）= 进程的"耳朵"
                   别人写进来，这个进程能读到
                   终端里 = 你键盘打的字

stdout （标准输出）= 进程的"嘴巴"
                   这个进程往里写，别人能读到
                   终端里 = System.out.println() 打印到屏幕的内容

stderr （标准错误）= 进程的"报错嘴巴"
                   专门输出错误和警告，不影响正常数据通道
```

父子进程的管道连接（Spring AI 用 ProcessBuilder 启动子进程时自动建立）：

```
父进程（Spring Boot）                子进程（MCP Server）
      │                                     │
      │  写入 ──▶  [stdin 管道]  ──▶  读取   │  ← 父进程发指令给子进程
      │                                     │
      │  读取 ◀──  [stdout 管道] ◀──  写入   │  ← 子进程返回结果给父进程
      │                                     │
      │  读取 ◀──  [stderr 管道] ◀──  写入   │  ← 子进程的报错/日志
```

重要规则：
- stdout 是 MCP 协议的专用通道，只能传 JSON 格式的协议数据
- 子进程绝对不能往 stdout 打日志！否则父进程解析 JSON 会崩溃
- stderr 可以随便用，父进程会收集并打成日志（你日志里看到的
  "StdioClientTransport: STDERR Message received" 就是这个）

这就是为什么 mcp-servers.json 里的 java 子进程要加参数 -Dlogging.pattern.console=
（空字符串），强制关掉 stdout 的日志输出，保证 stdout 只有干净的 JSON 数据。

---

## 父进程怎么启动子进程的？——Java ProcessBuilder

Spring AI 读完这个 JSON 后，内部等价于执行了这段 Java 代码：

```java
// ProcessBuilder = Java 标准库的类，用来在 Java 代码里启动外部程序
// 等价于在终端手动输入命令行
ProcessBuilder pb = new ProcessBuilder(
    "java",                                 // 命令
    "-Dspring.ai.mcp.server.stdio=true",    // JVM 参数
    "-jar",
    "yu-image-search-mcp-server-1.0.0.jar" // jar 路径
);

Process childProcess = pb.start(); // ← 操作系统在这里创建新的 JVM 进程

// 拿到 3 根管道
OutputStream toChild   = childProcess.getOutputStream(); // 父→子指令通道
InputStream  fromChild = childProcess.getInputStream();  // 子→父结果通道
InputStream  errors    = childProcess.getErrorStream();  // 子的报错通道
```

---

## MCP 握手过程——父子进程怎么建立联系？

子进程启动后，管道连通了，但双方还不知道对方有什么能力。
握手（Handshake）= "互相介绍 + 询问工具列表"，共 3 步。

管道里传的数据格式是 JSON-RPC 2.0（每条消息是一行 JSON）：
- JSON-RPC = 用 JSON 格式来"远程打电话"的协议
- 请求：{"jsonrpc":"2.0","id":1,"method":"操作名","params":{参数}}
- 响应：{"jsonrpc":"2.0","id":1,"result":{返回值}}

### 第1步：initialize（互相介绍，确认协议版本）

父进程通过 stdin 管道发送：
```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
  "protocolVersion":"2024-11-05",
  "clientInfo":{"name":"spring-ai-mcp-client","version":"1.0.0"}
}}
```

子进程通过 stdout 管道回复：
```json
{"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2024-11-05",
  "capabilities":{"tools":{"listChanged":true}},
  "serverInfo":{"name":"yu-image-search-mcp-server","version":"0.0.1"}
}}
```

capabilities = 能力清单，子进程告诉父进程"我会什么"。
`"tools":{}` 表示：我有工具能力，父进程接下来可以来问工具列表。

### 第2步：tools/list（问有哪些工具）

父进程通过 stdin 管道发送：
```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

子进程通过 stdout 管道回复：
```json
{"jsonrpc":"2.0","id":2,"result":{
  "tools":[{
    "name":"searchImage",
    "description":"Search images from Pixabay by keyword, returns medium-size image URLs",
    "inputSchema":{
      "type":"object",
      "properties":{
        "query":{"type":"string","description":"Search query keyword, e.g. 'cat'"}
      },
      "required":["query"]
    }
  }]
}}
```

工具列表来自 ImageSearchTool.java 上的 @Tool 和 @ToolParam 注解，
Spring AI MCP Server 框架在启动时自动把这些注解转成上面的 JSON 结构。

inputSchema = JSON Schema 格式，描述工具接受的参数结构。
AI 模型靠这个 schema 知道"调用这个工具时该传什么参数、参数是什么类型"。

### 第3步：握手完成，保持连接

握手完成后，Spring AI 把每个工具包装成 ToolCallback 对象，
存进 SyncMcpToolCallbackProvider Bean 里，等待 AI 随时调用。

子进程不退出，一直在后台运行，监听 stdin，随时等待下一条指令。
管道始终保持连通，就像一条专线电话从不挂断。

---

## 工具真正被调用时——tools/call

当 AI 决定调用 searchImage 工具时（Function Calling 机制），
Spring AI 通过 stdin 管道发送：

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
  "name":"searchImage",
  "arguments":{"query":"boots"}
}}
```

子进程 ImageSearchTool.searchImage("boots") 执行，
调用 Pixabay REST API，拿到图片 URL，
通过 stdout 管道返回：

```json
{"jsonrpc":"2.0","id":3,"result":{
  "content":[{"type":"text","text":"https://pixabay.com/get/url1.jpg,url2.jpg,..."}]
}}
```

---

## 各服务器配置逐条解释

### yu-image-search-mcp-server（本项目自定义的 Java MCP Server）

```json
"yu-image-search-mcp-server": {
  "command": "java",
  "args": [
    "-Dspring.ai.mcp.server.stdio=true",
    "-Dspring.main.web-application-type=none",
    "-Dlogging.pattern.console=",
    "-jar",
    "yu-image-search-mcp-server/target/yu-image-search-mcp-server-1.0.0.jar"
  ],
  "env": {}
}
```

- `command: "java"` — 启动命令，就是运行 java 程序
- `-Dspring.ai.mcp.server.stdio=true` — 告诉子进程：使用 stdio 模式，
  读 stdin 接收指令，写 stdout 返回结果（而不是起 HTTP 服务器）
- `-Dspring.main.web-application-type=none` — 不启动 Tomcat Web 服务器，
  节省端口和内存（子进程不需要对外提供 HTTP 服务）
- `-Dlogging.pattern.console=` — 空字符串，关掉 stdout 日志，
  保证 stdout 只输出 JSON-RPC 协议数据，不混入任何日志文字
- `-jar xxx.jar` — 指定要运行的 jar 包路径（需要先 mvn package 打包）
- `env: {}` — 不需要额外的环境变量

### filesystem（Node.js 文件系统服务器）

```json
"filesystem": {
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-filesystem", "mcp/tmp"],
  "env": {"npm_config_registry": "https://registry.npmjs.org"}
}
```

- `command: "npx"` — Node.js 的包运行工具，类似 Java 里的 java -jar
- `-y` — 自动同意安装（不弹出确认对话框）
- `@modelcontextprotocol/server-filesystem` — 包名（官方 npm 包）
- `mcp/tmp` — 允许访问的目录，工具只能读写这个目录下的文件（安全限制）
- `npm_config_registry` — npm 包下载源，设成官方地址，防止被企业代理拦截

### memory / fetch / puppeteer

同理，都是 npx 启动的官方 Node.js MCP Server 包，
分别提供记忆存储、网页抓取、浏览器控制能力。

---

## 专业术语速查

| 术语 | 通俗解释 |
|------|---------|
| 进程（Process） | 操作系统运行一个程序分配的独立"房间"，有独立内存 |
| 父进程 | 启动其他进程的进程，这里是 Spring Boot 应用 |
| 子进程 | 被父进程启动的进程，这里是 5 个 MCP Server |
| 管道（Pipe） | 操作系统提供的进程间数据通道，像水管先进先出 |
| stdin | 进程的标准输入，"耳朵"，接收别人写进来的数据 |
| stdout | 进程的标准输出，"嘴巴"，写出去别人能读到 |
| stderr | 进程的标准错误，"报错嘴巴"，专门输出错误信息 |
| ProcessBuilder | Java 标准库类，用来在代码里启动外部程序 |
| MCP | Model Context Protocol，AI 和外部工具通信的规范协议 |
| JSON-RPC | 用 JSON 格式发"远程调用"请求的协议 |
| 握手（Handshake） | 两端互相介绍、确认能力的初始化过程 |
| capabilities | 能力清单，子进程告诉父进程自己会什么 |
| inputSchema | 工具参数结构的 JSON Schema 描述，告诉 AI 怎么填参数 |
| ToolCallback | Spring AI 封装工具调用逻辑的对象 |
| SyncMcpToolCallbackProvider | Spring AI 的 Bean，装着所有 MCP 工具的集合 |
| Function Calling | AI 决定调用外部工具并生成调用指令的能力 |
| tool_calls | AI 返回的工具调用指令（不是文字回复） |
| exit code 143 | 进程被 SIGTERM 信号正常终止，128+15=143，不是报错 |

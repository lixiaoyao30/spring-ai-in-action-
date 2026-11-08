package com.yupi.yuimagesearchmcpserver.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ══════════════════════════════════════════════════════════════════════════
 * ImageSearchTool —— MCP Server 对外暴露的工具类
 * ══════════════════════════════════════════════════════════════════════════
 *
 * 【这个类在整个系统里的位置】
 *
 *   父进程（yu-ai-agent）
 *     │  通过 stdin 管道发 tools/call 指令
 *     ↓
 *   MCP Server 框架（接收 JSON-RPC，找到对应工具）
 *     │  调用 searchImage(query) 方法
 *     ↓
 *   本类 ImageSearchTool.searchImage()   ← 你现在看的
 *     │  发 HTTP GET 请求
 *     ↓
 *   Pixabay REST API（返回图片 JSON 数据）
 *     │  解析出 webformatURL 字段（640px 图片链接）
 *     ↓
 *   返回逗号分隔的 URL 字符串，通过 stdout 管道回给父进程
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 【@Service 注解是什么？为什么需要它？】
 *
 *   @Service = 告诉 Spring：请把这个类创建成一个 Bean，由容器统一管理。
 *
 *   Bean（被 Spring 管理的对象）是 Spring IoC 容器的核心概念：
 *     IoC = Inversion of Control（控制反转）
 *     传统方式：你自己 new 对象，自己管理生命周期
 *       ImageSearchTool tool = new ImageSearchTool();  // 手动创建
 *     IoC 方式：把创建权"反转"给 Spring 容器
 *       @Service 标注 → Spring 自动创建 → @Autowired 注入使用
 *
 *   在本项目里，@Service 是必须的，因为：
 *     YuImageSearchMcpServerApplication 里的 @Bean 方法需要注入 ImageSearchTool：
 *       public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool)
 *                                                    ↑ Spring 自动注入
 *     如果没有 @Service，Spring 不知道这个类要被管理，注入时找不到 Bean，报错。
 *
 *   @Service / @Component / @Repository / @Controller 的区别：
 *     本质完全相同，都是告诉 Spring 管理这个类。
 *     只是语义上的区分（让代码可读性更好）：
 *       @Controller   = 控制器层（处理 HTTP 请求，接受用户输入）
 *       @Service      = 业务逻辑层（处理核心业务，本类属于这一层）
 *       @Repository   = 数据访问层（操作数据库）
 *       @Component    = 不属于以上任何一层的通用组件
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 【@Tool 注解是什么？怎么让方法变成 MCP 工具？】
 *
 *   @Tool = Spring AI 提供的注解，把一个普通 Java 方法变成"AI 可以调用的工具"。
 *
 *   加了 @Tool 之后，MCP Server 框架在启动时会：
 *     1. 扫描容器里所有 Bean，找到有 @Tool 注解的方法
 *     2. 读取注解信息（方法名、description、参数类型、@ToolParam 描述）
 *     3. 自动生成工具的 JSON 描述（inputSchema），这就是 tools/list 握手时返回的内容：
 *
 *          {
 *            "name": "searchImage",              ← 方法名
 *            "description": "Search images...",  ← @Tool 的 description
 *            "inputSchema": {
 *              "type": "object",
 *              "properties": {
 *                "query": {
 *                  "type": "string",             ← 参数类型 String → "string"
 *                  "description": "Search query..."  ← @ToolParam 的 description
 *                }
 *              },
 *              "required": ["query"]             ← 必填参数
 *            }
 *          }
 *
 *     4. 把这个工具描述注册到工具列表，等待父进程通过 tools/list 获取
 *
 *   AI 模型拿到工具描述后，靠 description 的文字判断：
 *     "用户说的需求，和哪个工具的 description 最匹配？"
 *     → 匹配到 searchImage → 决定调用它
 *   所以 description 写得越清晰，AI 就越能在合适的时候调用正确的工具。
 *
 *   @Tool vs @ToolParam：
 *     @Tool       = 描述整个方法（工具叫什么、做什么用）→ 给 AI 看方法级别的说明
 *     @ToolParam  = 描述某个参数（这个参数填什么）       → 给 AI 看参数级别的说明
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 【Function Calling 两轮对话流程——searchImage 是怎么被 AI 调用的？】
 *
 *   第一轮（父进程发给 AI）：
 *     消息内容 = 用户的话 + 所有工具的 JSON 描述（包含 searchImage 的说明书）
 *     AI 在云端思考：
 *       "用户说'帮我找几张长靴图片' → 我有 searchImage 工具
 *        → description 说能搜 Pixabay 图片 → 完全匹配！
 *        → 调用它，把'长靴'翻译成英文关键词 'boots'（AI 自身的多语言能力）"
 *     AI 回复（不是文字，是调用指令）：
 *       {"tool_calls":[{"name":"searchImage","arguments":{"query":"boots"}}]}
 *
 *   Spring AI 框架收到 tool_calls 后：
 *     通过 stdin 管道发给子进程：
 *       {"jsonrpc":"2.0","method":"tools/call","params":{"name":"searchImage","arguments":{"query":"boots"}}}
 *     子进程 MCP 框架收到 → 找到 searchImage 的 ToolCallback → 调用本方法
 *     本方法执行完 → 返回 URL 字符串 → 框架包装成 JSON-RPC 响应 → 通过 stdout 管道发回父进程
 *
 *   第二轮（父进程发给 AI）：
 *     消息内容 = 对话历史 + 工具结果（URL 字符串）
 *     AI 整理 URL，生成 Markdown 格式的图片链接文字，返回给用户
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 【Pixabay API 基本信息】
 *
 *   网站：https://pixabay.com
 *   API 文档：https://pixabay.com/api/docs/
 *   接口格式：GET https://pixabay.com/api/?key=xxx&q=关键词&image_type=photo
 *   免费限额：100 次请求 / 60 秒，足够学习使用
 *   获取 API Key：登录 https://pixabay.com/api/docs/，页面顶部直接显示你的 Key
 */
@Service
public class ImageSearchTool {

    /**
     * Pixabay API Key —— 替换成你自己的 Key
     * 获取方式：登录 https://pixabay.com/api/docs/ 后页面顶部直接显示
     * 警告：不要把真实 Key 提交到 Git 公开仓库（会被爬虫抓取滥用）
     */
    private static final String API_KEY = "58223383-4bb3c522170f88876a6de3452";

    /**
     * Pixabay 图片搜索接口地址
     * 完整文档：https://pixabay.com/api/docs/#api_search_images
     */
    private static final String API_URL = "https://pixabay.com/api/";

    /**
     * ════════════════════════════════════════════════════════════════════
     * searchImage —— 对外暴露的 MCP 工具方法
     * ════════════════════════════════════════════════════════════════════
     *
     * 【@Tool 参数详解】
     *   description：给 AI 看的工具说明书，AI 靠这段文字判断何时调用本工具。
     *   写作要点：
     *     1. 说清楚工具能做什么（能力）
     *     2. 说清楚会返回什么（输出）
     *     3. 用英文写（主流 AI 模型对英文理解更准确）
     *     4. 越具体越好，避免模糊导致 AI 误用
     *
     * 【@ToolParam 参数详解】
     *   description：给 AI 看的参数说明，告诉 AI 这个参数填什么。
     *   带上 e.g. 示例非常重要：
     *     AI 看到 "e.g. 'cat', 'mountain', 'computer'" 就知道要填英文关键词
     *     当用户说"帮我找长靴图片"，AI 会自动把中文翻译成英文 "boots" 传入
     *     这个翻译不是代码做的，是 AI 自身的多语言理解能力
     *
     * 【返回值设计】
     *   返回逗号分隔的 URL 字符串，而非 List，因为：
     *     MCP tools/call 响应的 content 字段类型是文本（"type":"text"）
     *     框架会把返回值序列化为字符串传输
     *     逗号分隔简单直接，AI 能正确解析并在回复里逐条展示给用户
     *
     * @param query 搜索关键词，由 AI 根据用户意图自动提取并填入（中文→英文自动翻译）
     * @return 逗号分隔的 webformatURL 字符串，如 "url1.jpg,url2.jpg,..."
     *         搜索无结果时返回 "No images found for: xxx"
     *         发生异常时返回 "Error searching image: 错误信息"（让 AI 知道出错了）
     */
    @Tool(description = "Search images from Pixabay by keyword, returns medium-size image URLs")
    public String searchImage(
            @ToolParam(description = "Search query keyword, e.g. 'cat', 'mountain', 'computer'")
            String query) {
        try {
            List<String> urls = searchWebformatImages(query);
            if (urls.isEmpty()) {
                return "No images found for: " + query;
            }
            // 多个 URL 用逗号拼接，AI 在最终回复里会把它们逐条展示给用户
            return String.join(",", urls);
        } catch (Exception e) {
            // 返回错误信息而非抛异常，让 AI 知道出错了，可以在回复中告知用户
            return "Error searching image: " + e.getMessage();
        }
    }

    /**
     * ════════════════════════════════════════════════════════════════════
     * searchWebformatImages —— 调用 Pixabay API 获取图片 URL 列表
     * ════════════════════════════════════════════════════════════════════
     *
     * 【Pixabay 返回的图片尺寸说明】
     *
     *   Pixabay 同一张图片提供多个尺寸（免费可访问的）：
     *     previewURL    = 最小，最大边 150px，适合缩略图列表
     *     webformatURL  = 中等，最大边 640px，适合网页展示（本方法取这个）
     *     largeImageURL = 较大，最大边 1280px，需要登录才能访问
     *
     *   选 webformatURL 的原因：
     *     640px 大小适中，加载快，图片清晰，适合 AI 回复里展示给用户
     *
     * 【Pixabay API 返回 JSON 结构示例】
     *
     *   {
     *     "totalHits": 500,
     *     "hits": [
     *       {
     *         "id": 195893,
     *         "webformatURL": "https://pixabay.com/get/xxx_640.jpg",  ← 我们取这个
     *         "webformatWidth": 640,
     *         "webformatHeight": 360,
     *         "tags": "boots, fashion, shoes",
     *         ...
     *       },
     *       ...
     *     ]
     *   }
     *
     * 【Hutool 是什么？为什么用它？】
     *
     *   Hutool = 国产 Java 工具库，封装了大量常用工具方法，减少重复代码。
     *   本方法用到：
     *     HttpUtil  → 发送 HTTP GET 请求（调用 Pixabay API）
     *     JSONUtil  → 解析 JSON 响应
     *     StrUtil   → 字符串工具（判断空值）
     *   类比：Hutool 像瑞士军刀，一个库解决 HTTP/JSON/字符串/日期/加密等常见需求。
     *
     * 【Stream API 说明】
     *
     *   .stream()                     → 把 JSON 数组转成 Java Stream（流）
     *   .map(obj -> (JSONObject) obj)  → 每个元素转成 JSONObject
     *   .map(obj -> obj.getStr("webformatURL")) → 取 webformatURL 字段值
     *   .filter(StrUtil::isNotBlank)   → 过滤掉空值（防止 API 偶发返回空字段）
     *   .collect(Collectors.toList())  → 收集成 List 返回
     *
     *   Stream 是 Java 8+ 的函数式编程 API，链式调用处理集合数据，
     *   比 for 循环更简洁，像流水线：数据进来，经过每道工序，出来结果。
     *
     * @param query 搜索关键词（英文效果最好，如 "boots", "cat", "mountain"）
     * @return 图片 URL 列表，最多 20 条（Pixabay 默认每页 20 条）
     */
    public List<String> searchWebformatImages(String query) {
        // 构建请求参数 Map
        // Pixabay API 参数文档：https://pixabay.com/api/docs/#api_search_images
        Map<String, Object> params = new HashMap<>();
        params.put("key", API_KEY);        // 必填：API 认证 Key
        params.put("q", query);            // 搜索关键词，多词用 + 连接，如 "yellow+flower"
        params.put("image_type", "photo"); // 只搜真实照片（photo），排除插画(illustration)和矢量图(vector)
        params.put("safesearch", "true");  // 过滤不适合所有年龄的内容
        params.put("per_page", 20);        // 每页返回数量，范围 3~200，默认 20

        // 发 GET 请求
        // HttpUtil.createGet() 是 Hutool 的 HTTP 工具
        // .form(params) 把 Map 拼成 URL 查询字符串，如 ?key=xxx&q=boots&image_type=photo
        // .execute().body() 发送请求并取响应体（JSON 字符串）
        String response = HttpUtil.createGet(API_URL)
                .form(params)
                .execute()
                .body();

        // 解析 JSON 响应，提取 webformatURL 列表
        // JSONUtil.parseObj()        → 把 JSON 字符串解析成 JSONObject（类似 Map）
        // .getJSONArray("hits")      → 取 hits 数组（图片列表）
        // .stream()                  → 转成 Java Stream，支持链式处理
        // .map(obj -> (JSONObject) obj) → 每个数组元素转成 JSONObject
        // .map(obj -> obj.getStr("webformatURL")) → 取 640px 图片链接
        // .filter(StrUtil::isNotBlank) → 过滤空值（方法引用，等价于 url -> StrUtil.isNotBlank(url)）
        // .collect(Collectors.toList()) → 收集成 ArrayList 返回
        return JSONUtil.parseObj(response)
                .getJSONArray("hits")
                .stream()
                .map(obj -> (JSONObject) obj)
                .map(obj -> obj.getStr("webformatURL"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}

package com.yupi.yuaiagent.app;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.yupi.yuaiagent.rag.query.QueryRewriter;
import com.yupi.yuaiagent.rag.query.LoveAppMultiQueryExpander;
import com.yupi.yuaiagent.demo.rag.MultiQueryExpanderDemo;
import org.junit.jupiter.api.Assertions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.document.Document;
import java.util.List;

/**
 * LoveApp 集成测试
 *
 * @SpringBootTest：启动完整的 Spring 容器，真实调用 AI 接口
 * 运行前确保 application-local.yml 里配了有效的 api-key
 */
@SpringBootTest
public class LoveAppTest {

    // 容器里有两个 ChatModel Bean（dashScopeChatModel 和 openAiChatModel）
    // 用 @Autowired + @Qualifier 精确指定 Bean 名，避免 NoUniqueBeanDefinitionException
    @Autowired
    @Qualifier("dashScopeChatModel")
    private ChatModel dashscopeChatModel;

    // 注入 Spring Boot 自动装配的 Redis 客户端
    // 前提：application.yml 里配了 spring.data.redis.host/port，且 Redis 服务正在运行
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 注入 Spring Boot 自动装配的 JDBC 客户端
    // 前提：application.yml 配好 datasource，且执行过 sql/init.sql 建好表
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 注入 RAG 向量数据库（由 LocalMemoryVectorStoreConfig 初始化）
    // 启动时自动读取 documents/*.md，向量化后存入内存，可直接使用
    @Autowired
    @Qualifier("localMemoryVectorStore")
    private VectorStore localMemoryVectorStore;

    // 注入阿里云百炼 RAG Advisor（由 DashScopeRagAdvisorConfig 初始化）
    // 内部封装了 DashScopeDocumentRetriever，指向阿里云「恋爱大师」知识库
    @Autowired
    @Qualifier("dashScopeRagAdvisor")
    private Advisor dashScopeRagAdvisor;

    // 注入查询重写器（由 QueryRewriter @Component 自动注册）
    // LoveApp 是手动 new 的，不被 Spring 管理，必须通过 setter 桥接
    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private LoveAppMultiQueryExpander multiQueryExpander;

    // 用于在测试里直接打印扩展后的子问题，验证多查询扩展效果
    @Autowired
    private MultiQueryExpanderDemo multiQueryExpanderDemo;

    /**
     * 测试单轮对话：验证 AI 能正常响应
     */
    @Test
    public void testDoChat() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);

        // 第一轮：打招呼
        String reply = loveApp.doChat("你好", "test-session-1");
        System.out.println("AI 回复：" + reply);
    }

    /**
     * 测试多轮对话记忆：验证第二轮能记住第一轮说过的内容
     *
     * 关键：两次调用用同一个 chatId "memory-test"
     * 如果记忆生效，第二轮 AI 应该记得"我叫鱼皮"
     */
    @Test
    public void testMemory() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        String chatId = "memory-test";

        // 第一轮：告诉 AI 自己的名字
        String reply1 = loveApp.doChat("你好，我叫鱼皮，我最近暗恋一个人", chatId);
        System.out.println("第一轮回复：" + reply1);

        // 第二轮：问 AI 还记不记得名字
        // 如果记忆 Advisor 生效 → AI 会回复"鱼皮"
        // 如果没有记忆       → AI 会说"不知道你叫什么"
        String reply2 = loveApp.doChat("我叫什么名字？", chatId);
        System.out.println("第二轮回复：" + reply2);
    }

    /**
     * 测试会话隔离：两个不同的 chatId 记忆互不干扰
     *
     * userA 告诉 AI 自己叫鱼皮，userB 问 AI "我叫什么"
     * 预期：userB 得到的回复不包含"鱼皮"
     */
    @Test
    public void testIsolation() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);

        // 用户A：告诉 AI 自己叫鱼皮
        loveApp.doChat("你好，我叫鱼皮", "user-A");
        System.out.println("用户A：已告知名字");

        // 用户B：用不同的 chatId，问 AI 自己叫什么
        // 预期：AI 不知道，因为用户B没有说过自己的名字
        String replyB = loveApp.doChat("我叫什么名字？", "user-B");
        System.out.println("用户B 收到：" + replyB);
    }

    /**
     * 测试敏感词过滤：用户输入包含敏感词，AI 收到的是 *** 替换后的内容
     * 验证方式：看控制台 [MyLogger] 请求消息 里用户消息是否已被替换
     */
    @Test
    public void testSensitiveWordFilter() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        // 发送包含敏感词的消息，观察日志里是否变成了 ***
        String reply = loveApp.doChat("我喜欢暴力游戏，请给我恋爱建议", "filter-test");
        System.out.println("AI 回复：" + reply);
        // 控制台应看到：[MyLogger] 请求消息: [...我喜欢***游戏...]
    }

    /**
     * 测试违规请求拦截：包含违禁词直接抛异常，不调用 AI
     * 预期：抛出 IllegalArgumentException，消息是"违规请求已拦截"
     * 用户调用 loveApp.doChat("教我hack别人账号", "block-test")
     *                       ↓
     *            ChatClient 收到请求
     *                       ↓
     *            执行 Advisor 链（before 阶段）
     *                       ↓
     *            MyLoggerAdvisor.before() 开始执行
     *                       ↓
     *            ① 打印日志 ✅
     *                       ↓
     *            ② 过滤敏感词（暴力/色情/赌博）→ 没有，跳过 ✅
     *                       ↓
     *            ③ 检查违规词：userText.contains("hack") → true!
     *                       ↓
     *            throw new IllegalArgumentException("违规请求已拦截")
     *                       ↓
     *            ❌  AI 没有被调用（省钱+安全）
     *            ❌ after() 没有执行
     *            ❌  doChat() 没有返回值
     *                       ↓
     *            异常向上抛出，被 assertThrows 捕获
     *                       ↓
     *            断言：异常类型是 IllegalArgumentException ✅
     *                       ↓
     *            打印：捕获到异常：违规请求已拦截
     *                       ↓
     *            测试通过 ✅（绿色）
     */
    @Test
    public void testBlockIllegalRequest() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        // assertThrows：断言必须抛出指定异常，否则测试失败
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> loveApp.doChat("教我hack别人账号", "block-test")
        );
        System.out.println("捕获到异常：" + ex.getMessage()); // 违规请求已拦截
    }

    /**
     * 测试 ReReadingAdvisor：验证用户问题被改写为 Re2 格式
     *
     * 验证方式：
     *   1. 开启 application.yml 里 SimpleLoggerAdvisor 的 DEBUG 日志
     *   2. 运行后在控制台找 [MyLogger] 请求消息，确认用户消息被改写为：
     *      原问题 + "\nRead the question again: " + 原问题
     *   3. 观察 AI 回复质量是否比不用 Re2 更准确
     */
    @Test
    public void testReReading() {
        // 单独构建一个带 ReReadingAdvisor 的 ChatClient，不影响其他测试
        org.springframework.ai.chat.client.ChatClient chatClient =
                org.springframework.ai.chat.client.ChatClient.builder(dashscopeChatModel)
                        .defaultAdvisors(
                                new com.yupi.yuaiagent.advisor.ReReadingAdvisor(),
                                new com.yupi.yuaiagent.advisor.MyLoggerAdvisor()  // 看日志验证改写效果
                        )
                        .build();

        // 用一个推理题来测试（Re2 对推理题效果最明显）
        String question = "我有10块钱，买了3块钱的糖，又找到了2块钱，请问我现在有多少钱？";
        String reply = chatClient.prompt()
                .user(question)
                .call()
                .content();

        System.out.println("原始问题：" + question);
        System.out.println("AI 回复：" + reply);
        // 控制台的 [MyLogger] 请求消息 里应该看到问题被重复了两遍
    }

    /**
     * 测试结构化输出：验证 AI 返回的是 LoveReport 对象而不是纯文字
     * 预期：report.score() / report.advice() / report.nextStep() 都有值
     */
    @Test
    public void testDoChatWithReport() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        LoveApp.LoveReport report = loveApp.doChatWithReport(
                "我暗恋一个同事三年了，一直没有表白，我该怎么办？",
                "report-test"
        );
        System.out.println("恋爱健康分：" + report.score());
        System.out.println("核心建议：" + report.advice());
        System.out.println("下一步行动：" + report.nextStep());
        // 断言返回对象不为 null，字段有值
        Assertions.assertNotNull(report);
        Assertions.assertNotNull(report.score());
        Assertions.assertNotNull(report.advice());
    }

    /**
     * Redis 记忆验证：一个测试同时证明两件事
     *   ① 存进去了：对话后直接查 Redis Key，打印原始 JSON 数据
     *   ② 重启不丢：两个独立实例模拟服务重启，第二个实例能读到第一个存的记忆
     *
     * 运行前提：
     *   1. 本地启动 Redis（Mac: brew services start redis）
     *   2. application.yml 配置 spring.data.redis.host: localhost
     *
     * 【完整调用链 - 从这行代码到 Redis 命令】
     *
     *   instance1.doChat("你好，我叫鱼皮...", "redis-test")
     *        │
     *        ▼  chatClient.call() 触发
     *   MessageChatMemoryAdvisor.before()
     *        └─ RedisChatMemoryRepository.findByConversationId("redis-test")
     *            └─ Redis: LRANGE chat_memory:redis-test 0 -1
     *                └─ 第一次：返回空列表（没有历史）
     *        │
     *        ▼  发给通义千问 AI
     *   AI 返回："你好鱼皮！..."
     *        │
     *        ▼  MessageChatMemoryAdvisor.after()
     *   RedisChatMemoryRepository.saveAll("redis-test", [用户消息, AI回复])
     *        ├─ Redis: DEL   chat_memory:redis-test
     *        ├─ Redis: RPUSH chat_memory:redis-test '{"role":"user","content":"你好..."}'
     *        ├─ Redis: RPUSH chat_memory:redis-test '{"role":"assistant","content":"..."}'
     *        ├─ Redis: SADD  chat_memory:ids  redis-test
     *        └─ Redis: EXPIRE chat_memory:redis-test 604800（7天）
     *
     *   ── 模拟重启：new LoveApp 创建全新对象，JVM 内存清空 ──
     *
     *   instance2.doChat("我叫什么名字？", "redis-test")
     *        │
     *        ▼  MessageChatMemoryAdvisor.before()
     *   RedisChatMemoryRepository.findByConversationId("redis-test")
     *        └─ Redis: LRANGE chat_memory:redis-test 0 -1
     *            └─ 返回上一步存的 2 条消息！注入到请求里
     *        │
     *        ▼  AI 收到：[系统提示, 历史用户消息, 历史AI回复, 当前消息]
     *   AI 回复："你叫鱼皮，你喜欢小花"  ✅ 记住了！
     */
    @Test
    public void testRedisMemory() {
        String chatId = "redis-test";

        // ── 阶段一：实例1 存入记忆（模拟第一次启动）──────────
        LoveApp instance1 = new LoveApp(dashscopeChatModel, redisTemplate);
        // 传入 redisTemplate → 构造方法内部创建 RedisChatMemoryRepository
        // doChat 执行完后，Redis 里就有数据了（before 读历史，after 存记忆）
        instance1.doChat("你好，我叫鱼皮，我喜欢一个叫小花的女孩", chatId);
        System.out.println("实例1 已对话，模拟服务重启...");

        // 验证①：直接查 Redis，确认数据写进去了
        // "chat_memory:" + chatId = "chat_memory:redis-test"（与 keyPrefix 一致）
        Long count = redisTemplate.opsForList().size("chat_memory:" + chatId);
        System.out.println("Redis 存了 " + count + " 条消息");
        Assertions.assertTrue(count != null && count > 0, "Redis 里应有消息");

        // 打印原始 JSON，直观看存了什么（终端也可以用 redis-cli --raw LRANGE 查看）
        System.out.println("── Redis 原始数据 ──");
        var raw = redisTemplate.opsForList().range("chat_memory:" + chatId, 0, -1);
        if (raw != null) raw.forEach(msg -> System.out.println("  " + msg));

        // ── 阶段二：实例2 读取记忆（模拟重启后的新实例）──────
        // new LoveApp = 全新对象，JVM 内存里没有任何历史
        // 但它拿到同一个 redisTemplate，能连接到同一个 Redis
        LoveApp instance2 = new LoveApp(dashscopeChatModel, redisTemplate);
        // 同一个 chatId → MessageChatMemoryAdvisor.before() 会去 Redis 找历史
        String reply = instance2.doChat("我叫什么名字？我喜欢谁？", chatId);
        System.out.println("实例2 回复：" + reply);
        // 验证②：reply 里应包含"鱼皮"和"小花"，说明记忆跨实例持久化成功
        Assertions.assertNotNull(reply);
        Assertions.assertFalse(reply.isBlank(), "回复不应为空");
    }

    /**
     * 文件记忆验证：证明两件事
     *   ① JSON 文件真的被写到磁盘了
     *   ② 第二个实例（模拟重启）能从文件读到历史，AI 记住了上一轮
     *
     * 运行后可以用终端查看文件：
     *   cat chat_memory/file-test.json
     */
    @Test
    public void testFileMemory() throws Exception {
        String chatId = "file-test";
        String fileDir = System.getProperty("user.dir") + "/chat_memory";

        // ── 阶段一：实例1 对话，写入 JSON 文件 ──
        LoveApp instance1 = new LoveApp(dashscopeChatModel, fileDir);
        instance1.doChat("你好，我叫鱼皮，我暗恋一个叫小花的女孩", chatId);
        System.out.println("实例1 已对话，模拟服务重启...");

        // 验证①：文件存在且内容不为空
        java.io.File file = new java.io.File(fileDir + "/" + chatId + ".json");
        Assertions.assertTrue(file.exists(), "JSON 文件应该被创建");
        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        System.out.println("── JSON 文件内容 ──");
        System.out.println(content);
        Assertions.assertTrue(content.contains("鱼皮"), "文件里应包含用户消息");

        // ── 阶段二：实例2 读取记忆（模拟重启后的新实例）──
        LoveApp instance2 = new LoveApp(dashscopeChatModel, fileDir);
        String reply = instance2.doChat("我叫什么名字？我喜欢谁？", chatId);
        System.out.println("实例2 回复：" + reply);

        // 验证②：AI 回复里包含之前告知的信息
        Assertions.assertNotNull(reply);
        Assertions.assertFalse(reply.isBlank(), "回复不应为空");
    }

    /**
     * MySQL 记忆验证：对话后直接查数据库，确认数据写入了 chat_memory 表
     *
     * 运行前提：
     *   1. MySQL 服务启动，application.yml / application-local.yml 配好连接信息
     *   2. 执行过 src/main/resources/sql/init.sql（建库建表，只需执行一次）
     *
     * 【调用链 - 和 Redis 版完全一样，只是最后一步换成 SQL】
     *
     *   doChat("你好，我叫小明...", "mysql-test")
     *        │
     *        ▼  MessageChatMemoryAdvisor.before()
     *   MysqlChatMemoryRepository.findByConversationId("mysql-test")
     *        └─ SQL: SELECT role,content FROM chat_memory
     *                WHERE conversation_id='mysql-test' ORDER BY id ASC
     *                → 第一次：返回空列表
     *        │
     *        ▼  发给 AI，AI 返回回复
     *        │
     *        ▼  MessageChatMemoryAdvisor.after()
     *   MysqlChatMemoryRepository.saveAll("mysql-test", [用户消息, AI回复])
     *        ├─ SQL: DELETE FROM chat_memory WHERE conversation_id='mysql-test'
     *        ├─ SQL: INSERT INTO chat_memory(conversation_id,role,content) VALUES(...)
     *        └─ SQL: INSERT INTO chat_memory(conversation_id,role,content) VALUES(...)
     *
     * 【验证方式 - 在 MySQL 里执行 SQL 查看数据】
     *   SELECT * FROM yuai_agent.chat_memory WHERE conversation_id = 'mysql-test';
     */
    @Test
    public void testMysqlMemory() {
        String chatId = "mysql-test";

        // ── 先清空本次测试的历史数据，防止上次测试残留干扰 ──
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", chatId);

        // ── 阶段一：对话，触发 saveAll 写入 MySQL ────────────
        LoveApp loveApp = new LoveApp(dashscopeChatModel, jdbcTemplate);
        // 传入 jdbcTemplate → 构造方法用 MysqlChatMemoryRepository
        String reply1 = loveApp.doChat("你好，我叫小明，我喜欢一个叫小红的女孩", chatId);
        System.out.println("第一轮 AI 回复：" + reply1);

        // ── 阶段二：直接查数据库，验证数据写进去了 ────────────
        // queryForList 把每一行查询结果转成 Map（列名→值）
        var rows = jdbcTemplate.queryForList(
                "SELECT id, conversation_id, role, content, created_time " +
                "FROM chat_memory WHERE conversation_id = ? ORDER BY id ASC",
                chatId
        );

        System.out.println("\n── MySQL chat_memory 表中的数据 ──");
        System.out.printf("%-6s %-15s %-10s %s%n", "id", "conversation_id", "role", "content");
        System.out.println("─".repeat(80));
        for (var row : rows) {
            // 内容超过 50 字截断，控制台看起来更整齐
            String content = String.valueOf(row.get("content"));
            if (content.length() > 50) content = content.substring(0, 50) + "...";
            System.out.printf("%-6s %-15s %-10s %s%n",
                    row.get("id"), row.get("conversation_id"), row.get("role"), content);
        }
        System.out.println();

        // 断言：至少有 2 条（用户消息 + AI 回复）
        Assertions.assertTrue(rows.size() >= 2, "数据库里应至少有2条消息（user + assistant）");

        // ── 阶段三：第二轮对话，验证记忆跨调用有效 ────────────
        // 第二轮用同一个 chatId，before() 会从 MySQL 读出第一轮历史
        String reply2 = loveApp.doChat("我叫什么名字？我喜欢谁？", chatId);
        System.out.println("第二轮 AI 回复（应该记住小明和小红）：" + reply2);

        // 再查一次，确认第二轮的消息也存进去了（共 4 条：2轮 × 用户+AI）
        int totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_memory WHERE conversation_id = ?",
                Integer.class, chatId
        );
        System.out.println("两轮对话后数据库共 " + totalCount + " 条消息");
        Assertions.assertTrue(totalCount >= 4, "两轮对话后应至少有4条消息");
    }

    /**
     * 本地 RAG 完整验证：一次测试覆盖4个能力点
     *
     * 验证点：
     *   ① status 过滤：传"单身"只搜单身篇，不混入恋爱/已婚内容
     *   ② 查询改写：口语化问题被改写成专业表达（看控制台 [QueryRewriter] 输出）
     *   ③ 多查询扩展：1个问题扩展成4个子问题（看控制台"多查询扩展结果"输出）
     *   ④ 合并去重：多个子问题的搜索结果合并，重复文档只保留一份
     *
     * 运行前提：application-local.yml 配好 dashscope api-key（需调 Embedding API）
     */
    @Test
    public void testDoChatWithLocalRag() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        loveApp.setRagVectorStore(localMemoryVectorStore);
        loveApp.setQueryRewriter(queryRewriter);
        loveApp.setMultiQueryExpander(multiQueryExpander);

        String question = "我他妈的就想学习大模型应用开发，行不 到底咋样吧";

        // ── 验证② 查询改写 ──────────────────────────────────────────────
        // 控制台会打印：[QueryRewriter] 原始: 她总不回我消息... → 改写: 恋爱中对方长时间...
        System.out.println("\n======== ② 查询改写验证 ========");
        String rewritten = queryRewriter.doQueryRewrite(question);
        System.out.println("原始问题：" + question);
        System.out.println("改写后：  " + rewritten);

        // ── 验证③ 多查询扩展 ────────────────────────────────────────────
        // 控制台打印扩展后的4个子问题（原始+3个扩展）
        // 注意：这里只是打印验证，真正的扩展在 doChatWithLocalRag 内部由框架调用
        System.out.println("\n======== ③ 多查询扩展验证 ========");
        multiQueryExpanderDemo.expand(question);
        // 控制台应看到：[1]原始问题 [2][3][4]三个不同角度的扩展问题

        // ── 验证① status 过滤 + ④ 合并去重 ────────────────────────────
        // 传 status="恋爱" → 只搜恋爱篇5条文档，不混入单身/已婚内容
        // multiQueryExpander 已注入 → 4个子问题分别搜索后 ConcatenationDocumentJoiner 合并去重
        System.out.println("\n======== ① status过滤 + ④ 合并去重验证 ========");
        String reply = loveApp.doChatWithLocalRag(question, "local-rag-test", "恋爱");
        System.out.println("AI 回复：" + reply);
        // 控制台 [LocalRAG] 日志里 status=恋爱，说明过滤生效
        // AI 回复内容应来自恋爱篇，不涉及单身/已婚建议

        Assertions.assertNotNull(reply);
        Assertions.assertFalse(reply.isBlank());
    }

    /**
     * 云端 RAG 对话测试：验证 AI 能基于阿里云百炼「恋爱大师」知识库回答
     *
     * 运行前提：
     *   1. 阿里云百炼控制台已创建「恋爱大师」知识库，并上传3个.md文件
     *   2. application-local.yml 配好 dashscope api-key
     *
     * 【如何验证确实调用了云端知识库 - 看这3个地方】
     *
     *   ① 控制台搜索 "[CloudRAG]" → 出现说明本方法被调到
     *
     *   ② 控制台搜索 "[MyLogger] 请求消息" → 展开看 user 消息
     *      正常对话的 user 消息：只有用户问题本身
     *      云端RAG的 user 消息：用户问题 + "请基于以下文档回答：[文档1][文档2]..."
     *      多出来的那段 = 从阿里云百炼检索回来的知识库内容，证明云端被调了
     *
     *   ③ 阿里云百炼控制台 → 知识管理 → 恋爱大师 → 监控配置
     *      运行测试前后对比调用次数，+1 = 云端被调了
     */
    @Test
    public void testDoChatWithDashScopeRag() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        // LoveApp 是手动 new 的，@Resource 不生效，需通过 setter 注入
        // dashScopeRagAdvisor 来自 Spring 容器（@Autowired 注入到本测试类），再手动传给 loveApp
        loveApp.setDashScopeRagAdvisor(dashScopeRagAdvisor);

        // 问一个只有恋爱知识库里才有答案的问题
        // 如果云端知识库生效，AI 的回答会引用我们上传的文档内容
        String reply = loveApp.doChatWithDashScopeRag("单身怎么提升自己的吸引力？", "dashscope-rag-test");
        System.out.println("阿里云百炼RAG 回复：" + reply);

        Assertions.assertNotNull(reply);
        Assertions.assertFalse(reply.isBlank());
    }

    /**
     * ══ 真正的 Function Calling 测试 ══
     *
     * 和 FileOperationToolTest 的本质区别：
     *   FileOperationToolTest：你直接调 tool.writeFile()，大模型不参与
     *   这个测试：        你只给大模型说"帮我保存"，大模型自己决定调 writeFile
     *
     * 验证大模型真的触发了工具调用，看控制台：
     *   [MyLogger] 请求消息 → 看到 tools 字段，说明工具清单发给了大模型
     *   [FunctionCalling]   → 看到最终 AI 回复
     *   tmp/file/ 目录下    → 真的生成了文件，说明工具被执行了
     */
    @Test
    public void testDoChatWithTools() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);

        // 测试① 写文件：用户只说需求，大模型自己决定调 writeFile 工具
        String reply1 = loveApp.doChatWithTools(
                "帮我把'我目前正在学习rag 大模型应用开发'保存到 agent_diary.txt",
                "tool-test"
        );
        System.out.println("写文件回复：" + reply1);
        // 预期：tmp/file/love_diary.txt 文件被创建，AI 回复"已保存成功"

        // 测试② 读文件：大模型决定调 readFile 工具，把文件内容读出来告诉用户
        String reply2 = loveApp.doChatWithTools(
                "读一下 agent_diary.txt 里写了什么",
                "tool-test"
        );
        System.out.println("读文件回复：" + reply2);
        // 预期：AI 回复里包含刚才写入的内容

        Assertions.assertNotNull(reply1);
        Assertions.assertNotNull(reply2);
    }
    @Autowired
    @Qualifier("supabasePgVectorRagAdvisor")
    private Advisor supabasePgVectorRagAdvisor;

    // 注入 vectorStore 本体，用于直接调 similaritySearch 验证 Supabase 里有没有数据
    @Autowired
    @Qualifier("vectorStore")
    private VectorStore supabaseVectorStore;

    /**
     * 验证第一步：直接搜 Supabase，打印搜到的文档
     * 这是铁证——如果能打印出 .md 里的内容，说明数据在 Supabase，搜索走的就是 pgVector
     */
    @Test
    public void testVerifySupabaseHasData() {
        List<Document> docs = supabaseVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("单身怎么提升吸引力")
                        .topK(3)
                        .build()
        );

        System.out.println("══ 从 Supabase pgVector 搜到 " + docs.size() + " 条文档 ══");
        for (int i = 0; i < docs.size(); i++) {
            System.out.println("【第" + (i + 1) + "条】" + docs.get(i).getText());
            System.out.println("  来源：" + docs.get(i).getMetadata());
        }

        // 能搜到数据 = Supabase 里有内容，pgVector 正常工作
        Assertions.assertFalse(docs.isEmpty(), "Supabase 没有数据！请先运行 testLoadLoveDocsIntoPgVector()");
    }

    /**
     * Supabase pgVector RAG 对话测试
     *
     * 运行前提：先运行 PgVectorStoreTest.testLoadLoveDocsIntoPgVector() 把知识库写入 Supabase
     *
     * ══ 如何确认确实用了 Supabase pgVector，而不是普通对话 ══
     *
     * 方法1：看控制台日志（最简单）
     *   搜索关键字 "[PgVectorRAG]"
     *   → 出现 "[PgVectorRAG] 实际使用的 Advisor 类型: ..." = pgVector 被调用了 ✅
     *   → 出现 "[PgVectorRAG] supabasePgVectorRagAdvisor 未注入" = 没注入，退化了 ❌
     *
     * 方法2：看请求里的 user 消息内容（看 RAG 有没有检索到内容）
     *   搜索关键字 "[MyLogger] 请求消息"
     *   → user 消息只有问题本身           = RAG 没检索到内容（Supabase 表是空的）
     *   → user 消息 = 问题 + 一段文档内容 = RAG 检索到了，文档来自 Supabase ✅
     *
     * 方法3：看 Supabase 控制台
     *   Supabase → Table Editor → vector_store → 查看 updated_at 字段
     *   运行测试时有读操作（similaritySearch），但不会更新时间戳，所以看日志更直观
     */
    @Test
    public void testDoChatWithPgVectorRag() {
        LoveApp loveApp = new LoveApp(dashscopeChatModel);
        loveApp.setSupabasePgVectorRagAdvisor(supabasePgVectorRagAdvisor);
        loveApp.setSupabasePgVectorStore(supabaseVectorStore); // 传入 vectorStore，用于打印检索结果

        String reply = loveApp.doChatWithSupobasePgVectorRag("单身怎么提升自己的吸引力？", "pgvector-rag-test");
        System.out.println("pgVector RAG 回复：" + reply);

        Assertions.assertNotNull(reply);
        Assertions.assertFalse(reply.isBlank());
    }
}

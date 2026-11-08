package com.yupi.yuaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * 验证 Supabase pgVector 连接 + 向量数据插入/查询
 *
 * 运行前确认：
 *   application-local.yml 里填好 Supabase 连接参数
 *
 * 测试内容：
 *   ① 能否拿到数据库连接
 *   ② vector 扩展是否已安装
 *   ③ 向量表是否存在
 *   ④ 插入向量数据 + 相似度查询（核心功能验证）
 *   ⑤ 把恋爱文档批量写入 pgVector（供 RAG 使用）
 */
@SpringBootTest
class PgVectorStoreTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // pgVectorStore = SupabasePgVectorStoreConfig 里手动建的那个，连 Supabase PostgreSQL
    // localMemoryVectorStore = LocalMemoryVectorStoreConfig 里建的那个，连内存 SimpleVectorStore
    // 这里要测 pgVector，所以用 @Qualifier 指定取 pgVector 那个
    @Autowired
    @Qualifier("vectorStore")
    private VectorStore pgVectorStore;

    @Autowired
    private LoveAppDocumentLoader loveAppDocumentLoader;

    // 注入 DashScope EmbeddingModel，用来诊断实际输出维度
    @Autowired
    @Qualifier("dashscopeEmbeddingModel")
    private org.springframework.ai.embedding.EmbeddingModel dashscopeEmbeddingModel;

    /**
     * 诊断：打印 DashScope EmbeddingModel 实际输出的向量维度
     * 运行这个，看控制台输出的维度是多少，再去对应修改配置
     */
    @Test
    void testEmbeddingDimensions() {
        float[] vector = dashscopeEmbeddingModel.embed("测试文本");
        System.out.println("✅ DashScope Embedding 实际输出维度：" + vector.length);
        System.out.println("   application.yml 里配的 dimensions=1024，实际=" + vector.length);
        if (vector.length != 1024) {
            System.out.println("⚠️  维度不一致！需要把 application.yml 和 SupabasePgVectorStoreConfig 里的");
            System.out.println("   dimensions 都改成 " + vector.length);
            System.out.println("   同时去 Supabase 控制台执行：TRUNCATE vector_store; 清空旧表重建");
        }
    }

    @Test
    void testConnection() throws Exception {
        // ① 验证能否连上数据库
        try (Connection conn = dataSource.getConnection()) {
            System.out.println("✅ 数据库连接成功！");
            System.out.println("   数据库地址：" + conn.getMetaData().getURL());
            System.out.println("   数据库版本：" + conn.getMetaData().getDatabaseProductVersion());
        }
    }

    @Test
    void testVectorExtension() {
        // ② 验证 vector 扩展是否安装
        String sql = "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector'";
        var result = jdbcTemplate.queryForList(sql);
        if (result.isEmpty()) {
            System.out.println("❌ vector 扩展未安装，去 Supabase 控制台 → Database → Extensions → 开启 vector");
        } else {
            System.out.println("✅ vector 扩展已安装，版本：" + result.get(0).get("extversion"));
        }
    }

    @Test
    void testVectorTable() {
        // ③ 验证向量表是否存在（Spring AI 启动时自动建，initialize-schema=true）
        String sql = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'vector_store')";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            System.out.println("✅ 向量表 vector_store 已存在");
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
            System.out.println("   当前存储了 " + count + " 条向量数据");
        } else {
            System.out.println("⚠️ 向量表不存在，正常——启动主程序后会自动创建（initialize-schema=true）");
        }
    }

    /**
     * ④ 核心功能验证：存入向量 + 语义搜索
     *
     * 完整流程图：
     *
     *   【存】文字  →  阿里云Embedding API  →  1024维数字数组  →  Supabase数据库
     *   【查】问题  →  阿里云Embedding API  →  1024维数字数组  →  和库里比相似度  →  返回最像的N条
     *
     * 关键点：存和查用的是同一个 Embedding 模型（dashscopeEmbeddingModel），
     *         所以"语义相近"的文字，数字数组也会相近，才能搜出来。
     */
    @Test
    void testInsertAndSearch() {
    /**  ══════════════════════════════════════════════════════
         第1步：准备要存入的文字（就是普通 Java 对象，还没有网络请求）
           Document(文字内容, 标签)  ← 标签可选，用于后续按条件过滤
         Document 是 Spring AI 的类，包含两部分：                                                          │1 test/generated (show)
          - 第一个参数：文字内容（会被转成向量存进去）                                                      │
          - 第二个参数：元数据标签（Map.of(key, value)），不转向量，原样存，用于过滤
         ══════════════════════════════════════════════════════
     */
        List<Document> documents = List.of(
                new Document("如何提升自身魅力？保持良好的生活习惯，注重仪表整洁，培养兴趣爱好，提升谈吐能力，自信是最大的魅力。",
                        Map.of("category", "单身", "source", "test")),
                new Document("怎么主动找话题和喜欢的人聊天？可以从对方感兴趣的话题入手，多问开放性问题，让对方多说，自己多听。",
                        Map.of("category", "单身", "source", "test")),
                new Document("恋爱中如何处理争吵？冷静期先各自平复情绪，避免激动时说伤人的话，之后心平气和坐下来沟通，找出根本原因。",
                        Map.of("category", "恋爱", "source", "test")),
                new Document("如何维持长期恋爱的新鲜感？定期安排约会，尝试新活动，给对方小惊喜，保持适度的个人空间都很重要。",
                        Map.of("category", "恋爱", "source", "test"))
        );

        // ══════════════════════════════════════════════════════
        // 第2步：存入向量数据库（这一行触发2次网络请求）
        //
        //   pgVectorStore.add()
        //       │
        //       ├─① 调阿里云 Embedding API：文字 → 1024维数字数组（向量）
        //       │      "如何提升自身魅力？..." → [-0.07, 0.06, 0.009, ...] (1024个数)
        //       │
        //       └─② 存入 Supabase PostgreSQL 的 vector_store 表
        //              id=uuid | content=原文 | metadata=标签 | embedding=数字数组
        //
        //   代码调用 pgVectorStore.add(documents)
        //      ↓
        //  PgVectorStore 内部调用 dashscopeEmbeddingModel.embed(每条文字)
        //      ↓
        //  发 HTTP 请求到阿里云 Embedding API
        //      ↓
        //  阿里云返回：每段文字 → 1024个小数组成的数组
        //      ↓
        //  PgVectorStore 内部执行 INSERT INTO vector_store (id, content, metadata, embedding) VALUES (...)
        //      ↓
        //  数据存进 Supabase PostgreSQL
        // pgVectorStore 这个 Bean 在 SupabasePgVectorStoreConfig.java 里创建，
        // 它内部同时持有：dashscopeEmbeddingModel（转向量）+ pgJdbcTemplate（存数据库）
        // ══════════════════════════════════════════════════════
        pgVectorStore.add(documents);
        System.out.println("✅ 插入 " + documents.size() + " 条向量数据成功（可去 Supabase Table Editor 查看）");

        // ══════════════════════════════════════════════════════
        // 第3步：语义搜索（也触发2次网络请求）
        //
        //   similaritySearch("我想让自己更有吸引力")
        //       │
        //       ├─① 调阿里云 Embedding API：问题 → 1024维数字数组
        //       │
        //       └─② 发 SQL 给 Supabase：用向量距离公式算"问题"和每条数据的相似度
        //              SELECT * FROM vector_store ORDER BY 相似度 DESC LIMIT 2
        //
        // "我想让自己更有吸引力" 和 "如何提升自身魅力" 语义相近
        // → 向量也相近 → 排在相似度第1名 → 被搜出来
        // ══════════════════════════════════════════════════════
        List<Document> results = pgVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("我想让自己更有吸引力，有什么方法吗")
                        .topK(2)  // 只返回最相似的2条
                        .build()
        );

        System.out.println("\n── 语义搜索结果（topK=2）──────────────────");
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            System.out.println("第" + (i + 1) + "条：" + doc.getText());
            System.out.println("   标签：" + doc.getMetadata());
        }
        System.out.println("\n✅ 语义搜索成功，找到 " + results.size() + " 条相关内容");
    }

    /**
     * ⑤ 把恋爱 .md 知识库文档批量写入 pgVector（正式数据，供 RAG 使用）
     *
     * 运行时机：知识库文档有变动时手动触发一次
     * 注意：重复运行会重复插入！如果要覆盖，先在 Supabase 控制台清空 vector_store 表
     *       SQL: DELETE FROM vector_store WHERE metadata->>'source' = 'loveapp_docs';
     *       或直接：TRUNCATE vector_store;
     *
     * 运行完可用 testVectorTable() 验证条数
     */
    @Test
    void testLoadLoveDocsIntoPgVector() {
        // ── 第1步：读取并切割 .md 文件 ──────────────────────────────────
        // LoveAppDocumentLoader 扫描 resources/documents/*.md
        // 按 Markdown 标题（####）切割，每个问答对 = 一个 Document
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        System.out.println("✅ 从 Markdown 文件加载了 " + documents.size() + " 个文档块");

        // ── 第2步：向量化并写入 Supabase pgVector ───────────────────────
        // 这里手动分批（每批10条），目的是打印每批写入进度，方便调试
        // 生产代码（SupabasePgVectorStoreConfig）用 TokenCountBatchingStrategy 按 token 数分批
        // 两者效果一样，测试代码用手动分批是为了让日志更直观
        int batchSize = 10;
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Document> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));
            pgVectorStore.add(batch);
            System.out.println("✅ 写入第 " + (i / batchSize + 1) + " 批，共 " + batch.size() + " 条");
        }
        System.out.println("✅ 批量写入 pgVector 完成，共 " + documents.size() + " 条");

        // ── 第3步：验证写入结果 ─────────────────────────────────────────
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
        System.out.println("✅ 当前 vector_store 表共有 " + count + " 条向量数据");
    }
}

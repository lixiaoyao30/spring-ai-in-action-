package com.yupi.yuaiagent.rag;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * pgVector 向量数据库配置（Supabase PostgreSQL）
 *
 * ══════════════════════════════════════════════════════════
 * 一、为什么需要这个文件？（MySQL 和 PostgreSQL 的隔离问题）
 * ══════════════════════════════════════════════════════════
 *
 *   Spring Boot 默认只认 spring.datasource.* 这一个数据源配置。
 *   application.yml 里配的是 MySQL → Spring 自动建了一个叫 dataSource 的 Bean 连 MySQL。
 *
 *   如果让 pgVector 自动配置去工作，它会从容器里找 DataSource Bean，
 *   找到的是 MySQL 那个，然后对 MySQL 执行 PostgreSQL 专属命令 → 报错。
 *
 *   解决方案：在这里手动建专属 PostgreSQL 连接池（pgDataSource Bean）
 *   和 PgVectorStore Bean，让 pgVector 自动配置检测到已有 Bean 后自动跳过。
 *
 *   结果：容器里同时存在两个独立连接池，完全隔离，互不干扰：
 *     dataSource   → MySQL   ← Spring 自动建，MysqlChatMemoryRepository 用这个
 *     pgDataSource → PostgreSQL ← 本类手动建，pgVectorStore 用这个
 *
 * ══════════════════════════════════════════════════════════
 * 二、为什么用 DashScope 的 EmbeddingModel，不用 OpenAI 的？
 * ══════════════════════════════════════════════════════════
 *
 *   PgVectorStore 需要 EmbeddingModel 来做两件事：
 *     ① add(documents)时：把每段文字转成向量 → 存入 PostgreSQL
 *     ② similaritySearch(问题)时：把问题也转成向量 → 与库里向量比较相似度
 *
 *   关键约束：存和查必须用同一个 Embedding 模型！
 *     不同模型产生的向量在不同"数学空间"里，混用会导致相似度计算完全错误。
 *
 *   用 OpenAI 的在技术上可行，但本项目统一用 DashScope（阿里云百炼）即可。
 *
 * ══════════════════════════════════════════════════════════
 * 三、@Bean 命名的三种写法（等价，推荐第二种）
 * ══════════════════════════════════════════════════════════
 *
 *   写法一：@Bean + @Qualifier（旧写法，语义不准确，@Qualifier 本意是"标签"不是"命名"）
 *     @Bean
 *     @Qualifier("pgDataSource")
 *     public DataSource pgDataSource() { ... }
 *
 *   写法二：@Bean(name = "xxx") ← 推荐，正规的 Bean 命名方式
 *     @Bean(name = "pgDataSource")
 *     public DataSource pgDataSource() { ... }
 *
 *   写法三：@Bean 不写 name，Bean 名默认等于方法名
 *     @Bean
 *     public DataSource pgDataSource() { ... }
 *     // Bean 名自动变成 "pgDataSource"（方法名），与写法二效果完全一样
 *
 *   参数注入时用 @Qualifier 按名字取值（这才是 @Qualifier 的主要用途）：
 *     public PgVectorStore pgVectorStore(
 *         @Qualifier("pgDataSource") DataSource ds,              // 取 PostgreSQL 那个
 *         @Qualifier("dashscopeEmbeddingModel") EmbeddingModel em) { ... }
 *
 *   不加 @Qualifier 时 Spring 按类型找：
 *     同类型只有1个 → 直接注入，没问题
 *     同类型有2个（如 DataSource: MySQL + PostgreSQL）→ Spring 不知道给哪个 → 启动报错
 *
 * 连接参数来自 application-local.yml（不提交 Git，本地填真实值）：
 *   spring.ai.vectorstore.pgvector.jdbc-url
 *   spring.ai.vectorstore.pgvector.jdbc-username
 *   spring.ai.vectorstore.pgvector.jdbc-password
 */
@Configuration
public class SupabasePgVectorStoreConfig {

    // ── 第1步：读配置文件里的值 ──────────────────────────────────────
    // @Value 从 application-local.yml 里读指定 key 的值，注入到字段

    @Value("${spring.ai.vectorstore.pgvector.jdbc-url}")
    private String pgJdbcUrl;

    @Value("${spring.ai.vectorstore.pgvector.jdbc-username}")
    private String pgUsername;

    @Value("${spring.ai.vectorstore.pgvector.jdbc-password}")
    private String pgPassword;

    // ── 第2步：创建 PostgreSQL 专属连接池 ──────────────────────────
    // @Bean(name="pgDataSource")：等价于方法名默认命名（本例方法名就叫 pgDataSource）
    // 不覆盖 Spring 自动创建的 MySQL dataSource，两个连接池完全独立
    // HikariCP = 业界最快的 Java 连接池，提前建好 N 个连接，用时借出，用完归还
    //如果不手动建，Spring 找不到，或者找到 MySQL 那个出错
    // 手动建 pgDataSource Bean 就是为了让 pgVector 有自己专用的数据库连接，不和 MySQL 混在一起
    @Bean(name = "pgDataSource")
    public DataSource pgDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pgJdbcUrl);
        ds.setUsername(pgUsername);
        ds.setPassword(pgPassword);
        ds.setDriverClassName("org.postgresql.Driver"); // PostgreSQL 驱动，不是 MySQL 驱动
        ds.setPoolName("HikariPool-pgvector");
        ds.setMaximumPoolSize(5);
        return ds;
    }

    // ── 第3步：创建 PgVectorStore，明确用 PostgreSQL 连接 ──────────
    // Bean 名叫 "vectorStore"，和 Spring AI 自动配置里的名字一样
    // Spring AI 自动配置条件：@ConditionalOnMissingBean(PgVectorStore.class)
    //   → 容器里已有 PgVectorStore Bean 时，自动配置跳过，不再建一个用 MySQL 的
    @Bean("vectorStore")
    public PgVectorStore pgVectorStore(
            @Qualifier("pgDataSource") DataSource pgDataSource,
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel,
            LoveAppDocumentLoader loveAppDocumentLoader,
            MyKeywordEnricher myKeywordEnricher) {
        JdbcTemplate pgJdbcTemplate = new JdbcTemplate(pgDataSource);
        PgVectorStore vectorStore = PgVectorStore.builder(pgJdbcTemplate, embeddingModel)
                .initializeSchema(true)
                .dimensions(1024)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .build();

        Long count = pgJdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
        if (count == null || count == 0) {
            List<Document> documents = myKeywordEnricher.enrichDocuments(loveAppDocumentLoader.loadMarkdowns());
            // DashScope 单次最多10条
            for (int i = 0; i < documents.size(); i += 10) {
                vectorStore.add(documents.subList(i, Math.min(i + 10, documents.size())));
            }
        }
        return vectorStore;
    }

    // ── 第4步：注册批处理策略 Bean ──────────────────────────────────
    // TokenCountBatchingStrategy：Spring AI 官方实现，按 token 数分批
    //
    // 无参构造默认值：
    //   编码方式：CL100K_BASE（GPT-4 / embedding-v3 系列通用 tiktoken 编码）
    //   每批上限：8191 token（OpenAI embedding 模型的标准上限）
    //   安全余量：10%（实际使用 8191 × 0.9 ≈ 7371 token/批）
    //
    // 为什么比手动 for 循环更好？
    //   旧做法：每批固定10条，短文档浪费配额，长文档可能一条就超限
    //   新做法：按 token 数控制，不管文档多长，都保证每批在模型限制内
//    @Bean
//    public BatchingStrategy batchingStrategy() {
//        return new TokenCountBatchingStrategy();
//    }

    // ── 第5步：解决 EmbeddingModel 歧义 ────────────────────────────
    // 容器里有 dashscopeEmbeddingModel 和 openAiEmbeddingModel 两个 EmbeddingModel Bean
    // （openAiEmbeddingModel 的存在取决于 spring.ai.model.embedding 配置，
    //   若配的是 dashscope 则 openAi 的不建；若两个都建了，@Primary 解决歧义）
    // @Primary = 按类型注入 EmbeddingModel 时优先用这个，不需要写 @Qualifier
    // primaryEmbeddingModel — 翻译官 ：把中文/其他语言变成数字
    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel dashscopeEmbeddingModel) {
        return dashscopeEmbeddingModel;
    }

    // ── 第6步：把 pgVector 搜索封装成 RAG Advisor Bean ──────────────
    // 和 DashScopeRagAdvisorConfig 里的 dashScopeRagAdvisor 模式完全一致：
    //   都是把"检索器"装进 RetrievalAugmentationAdvisor，供 LoveApp 直接放进 .advisors() 链
    //
    // 区别：
    //   dashScopeRagAdvisor   → 检索器发请求给阿里云百炼（云端搜索）
    //   supabasePgVectorRagAdvisor → 检索器查自己的 Supabase pgVector（本地数据库搜索）
    @Bean
    public Advisor supabasePgVectorRagAdvisor(@Qualifier("vectorStore") PgVectorStore vectorStore) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(
                        VectorStoreDocumentRetriever.builder()
                                .vectorStore(vectorStore)      // Supabase pgVector
                                .topK(3)                       // 返回最相似的 3 条
                                .similarityThreshold(0.5)      // 相似度低于 0.5 的不要
                                .build()
                )
                .build();
    }


}

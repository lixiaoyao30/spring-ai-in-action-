package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * ══════════════════════════════════════════════════════════
 * 恋爱知识库 — 本地内存向量库配置
 * ══════════════════════════════════════════════════════════
 *
 * 【这个类的职责 — 一句话】
 *   把 LoveAppDocumentLoader 读出的 Document 列表，
 *   向量化后存入内存向量数据库（SimpleVectorStore），供 doChatWithLocalRag 检索。
 *
 * ══════════════════════════════════════════════════════════
 * 一、Bean 名 dashscopeEmbeddingModel 从哪来的？
 * ══════════════════════════════════════════════════════════
 *
 *   这个名字是 Spring AI Alibaba 框架写死在自动配置类里的，不是我们起的：
 *ƒ
 *     // spring-ai-alibaba-starter-dashscope jar 包内部：
 *     @Bean("dashscopeEmbeddingModel")
 *     public EmbeddingModel dashscopeEmbeddingModel(...) { ... }
 *
 *     @Bean("openAiEmbeddingModel")
 *     public EmbeddingModel openAiEmbeddingModel(...) { ... }
 *
 *   我们只需要知道这些约定的名字，用 @Qualifier("dashscopeEmbeddingModel") 来取就行。
 *
 * ══════════════════════════════════════════════════════════
 * 二、OpenAI 的 EmbeddingModel 是怎么被"禁用"的？
 * ══════════════════════════════════════════════════════════
 *
 *   OpenAI Embedding 自动配置类上有条件注解（框架内部）：
 *     @ConditionalOnProperty(name="spring.ai.model.embedding", havingValue="openai")
 *   含义：只有 yml 里写 spring.ai.model.embedding=openai 时才建这个 Bean
 *
 *   我们在 application-local.yml 写的是：
 *     spring.ai.model.embedding=dashscope
 *   条件不满足 → OpenAI Embedding 自动配置整体跳过 → openAiEmbeddingModel Bean 从来不建
 *
 *   为什么要这样做？
 *     pgVectorStore 注入时需要一个 EmbeddingModel。
 *     如果容器里有两个 EmbeddingModel（dashscope + openAi），Spring 不知道给哪个 → 报错。
 *     通过 yml 配置从根源只保留一个，避免歧义。
 *     （SupabasePgVectorStoreConfig 里的 @Primary 是第二道防线，防止将来两个都被建出来的情况）
 *
 * ══════════════════════════════════════════════════════════
 * 三、RAG 离线阶段 · 第3步：向量转换和存储
 * ══════════════════════════════════════════════════════════
 *
 *   LoveAppDocumentLoader（第1、2步）
 *     读文件 → 切成15个Document
 *         │
 *         ▼  本类负责第3步
 *   EmbeddingModel（通义千问 Embedding API）
 *     "如何提升魅力？..." → [0.23, 0.87, 0.11, ...]（1536个数字）
 *         │
 *         ▼
 *   SimpleVectorStore（内存向量数据库）
 *     存储：向量 + 原始Document，共15条
 *         │
 *         ▼  等待用户提问（在线阶段）
 *   用户问 → 问题也变向量 → 找最相似的块 → 拼进 Prompt → AI 参考回答
 *
 * ══════════════════════════════════════════════════════════
 * 四、向量数据库选型对比
 * ══════════════════════════════════════════════════════════
 *
 *   ┌──────────────────────┬──────────┬────────────────────┐
 *   │ 实现                 │ 外部依赖  │ 适合场景           │
 *   ├──────────────────────┼──────────┼────────────────────┤
 *   │ SimpleVectorStore    │ 无       │ 学习/开发/演示 ✅   │
 *   │ PgVectorStore        │PostgreSQL│ 已有 PG 的项目      │
 *   │ RedisVectorStore     │Redis Stack│ 中小型生产         │
 *   │ ElasticsearchVectorStore│ ES    │ 已有 ES 的项目      │
 *   └──────────────────────┴──────────┴────────────────────┘
 *
 *   SimpleVectorStore 缺点：数据在 JVM 内存，重启后需重新加载（重新调 Embedding API）
 *   换成 PgVectorStore 只需改 @Bean 内的实现，其他代码不变
 */
@Slf4j
@Configuration
public class LocalMemoryVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    /**
     * 内存向量库（SimpleVectorStore），供恋爱本地 RAG（doChatWithLocalRag）检索使用。
     * 云端 RAG（doChatWithDashScopeRag）用的是 DashScopeRagAdvisorConfig，不依赖本 Bean。
     *
     * @param embeddingModel 通义千问 Embedding 模型，把文字转向量
     *   为什么用 @Qualifier：容器里可能有多个 EmbeddingModel，必须明确指定用哪个
     *   存和查必须同一个模型，否则向量空间不同，相似度计算会完全错误
     */
    @Bean
    public VectorStore localMemoryVectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {

        // ── 第1步：创建内存向量数据库 ──────────────────────────────
        // 绑定 embeddingModel：后续 add(documents) 时会自动调它把文字转成向量
        SimpleVectorStore simpleVectorStore = SimpleVectorStore
                .builder(embeddingModel)
                .build();

        // ── 第2步：加载文档 ────────────────────────────────────────
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        // 演示用 splitCustomized（200Token/块）；实际本项目文档短（约500Token/块），
        // 切太细反而导致语义断裂，生产建议换 splitDocuments（默认800Token/块）
        // List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documents);

        // ── 第2.5步：AI 关键词增强 ─────────────────────────────────
        // 调大模型给每块提取5个关键词，写入 metadata.keywords
        // 作用：弥补向量搜索的词汇差异，"穿搭"能命中"着装"相关的块
        // 注意：每个 Document 都会调一次 ChatModel API，文档多时耗时较长
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);
        log.info("[VectorStore] 关键词增强完成，共 {} 个文档块", enrichedDocuments.size());

        // ── 第3步：向量化 + 存储（一行完成两步）──────────────────────
        // add() 内部自动做：① 调 Embedding API 把每段文字转向量 ② 向量+原文一起存入内存
        // 注意：此处会调用阿里云 Embedding API，需要网络和有效 api-key
        simpleVectorStore.add(enrichedDocuments);
        log.info("[VectorStore] 向量数据库初始化完成，共存入 {} 条", enrichedDocuments.size());

        return simpleVectorStore;
    }
}

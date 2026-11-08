package com.yupi.yuaiagent.rag.query;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * RAG 检索过滤工厂
 *
 * 背景：
 *   文档存入向量库时，已按文件名贴了 metadata.status 标签（单身/恋爱/已婚）。
 *   但标签不会自动生效——默认搜索会把15条文档全部扫一遍，单身/恋爱/已婚混着返回。
 *
 * 这个工厂的作用：
 *   搜索时加上 filterExpression（status == 传入值），让标签真正起到过滤作用。
 *   传 "单身" → 只搜5条单身篇；传 "已婚" → 只搜5条已婚篇。
 *
 * 为什么是静态工厂而不是 @Bean？
 *   status 是运行时由调用方传入的（每个用户不同），Spring 启动时无法确定，
 *   所以每次调用时动态创建，不能提前注册成 Bean。
 *
 * 调用入口：LoveApp.doChatWithLocalRag(message, chatId, status)
 *   传了 status → 走这里，精准过滤
 *   不传（null）→ 不走这里，搜全部文档
 */
public class LoveAppRagCustomAdvisorFactory {

    /**
     * 创建自定义的 RAG 检索增强顾问
     *
     * @param vectorStore 向量存储（SimpleVectorStore 或 PgVectorStore 均可）
     * @param status      用户恋爱状态，用于过滤文档：单身 / 恋爱 / 已婚
     * @return 自定义的 RAG 检索增强顾问
     */
    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        // ── 第1步：构建过滤条件 ──────────────────────────────────────
        // .eq("status", status)：只检索 metadata.status == status 的文档
        // 用户是"单身" → 只搜单身篇；"已婚" → 只搜已婚篇
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status", status)
                .build();

        // ── 第2步：创建带过滤条件的检索器 ───────────────────────────
        VectorStoreDocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .filterExpression(expression) // 只搜对应状态的文档
                .similarityThreshold(0.5)      // 相似度低于 0.5 的文档不要
                .topK(3)                       // 最多返回 3 条最相关文档
                .build();

        // ── 第3步：组装完整的 RAG Advisor ───────────────────────────
        // ContextualQueryAugmenter 控制搜不到文档时的兜底策略：
        //   allowEmptyContext=true  → 搜不到也让 AI 凭自身知识回答
        //   allowEmptyContext=false → 搜不到直接返回"无法回答"（默认）
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
//                .queryAugmenter(ContextualQueryAugmenter.builder()
//                        .allowEmptyContext(true)
//                        .build())
                .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
                .build();
    }
}

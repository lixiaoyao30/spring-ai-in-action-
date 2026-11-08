package com.yupi.yuaiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ══════════════════════════════════════════════════════════════
 * DashScopeRagAdvisorConfig — 阿里云百炼知识库版 RAG 配置
 * ══════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════
 * 一、向量到底存在哪？阿里云百炼在这里干了什么？
 * ══════════════════════════════════════════════════════════════
 *
 *   本地 RAG（LocalMemoryVectorStoreConfig）vs 云端 RAG（本类）对比：
 *
 *   ┌──────────────┬────────────────────────────┬──────────────────────────────┐
 *   │              │ 本地 RAG                    │ 云端 RAG（本类）              │
 *   ├──────────────┼────────────────────────────┼──────────────────────────────┤
 *   │ 文档在哪     │ src/main/resources/documents│ 阿里云百炼控制台，网页上传    │
 *   │ 向量化谁做   │ 本地调阿里云 Embedding API  │ 阿里云百炼内部自动完成       │
 *   │ 向量存哪     │ JVM 内存（SimpleVectorStore）│ 阿里云百炼服务器（云端）     │
 *   │ 检索谁做     │ 本地 similaritySearch()     │ 阿里云百炼内部自动完成       │
 *   │ 本地存了什么 │ 向量数据（在内存里）         │ 什么都没存，只发问题收文档   │
 *   │ 重启后       │ 需重新向量化（重新调 API）   │ 无影响，数据在云端永久保存   │
 *   └──────────────┴────────────────────────────┴──────────────────────────────┘
 *
 *   阿里云百炼在云端 RAG 里干了 3 件事：
 *     ① 存储文档（你在控制台上传的那些文件）
 *     ② 向量化文档（把每段文字转成向量，存在它的服务器上）
 *     ③ 检索（收到你的问题 → 在云端搜索相关段落 → 返回给你）
 *   本地只做一件事：把用户问题发出去，收回相关文档，拼进 Prompt。
 *
 * ══════════════════════════════════════════════════════════════
 * 二、和 LocalMemoryVectorStoreConfig 的区别
 * ══════════════════════════════════════════════════════════════
 *
 *   LocalMemoryVectorStoreConfig（本地版）
 *     → 建的是 VectorStore Bean（SimpleVectorStore），供 doChatWithLocalRag 用
 *     → 本地存了向量数据，重启要重新向量化
 *
 *   本类（云端版）
 *     → 建的是 Advisor Bean，供 doChatWithDashScopeRag 直接放进 .advisors() 链
 *     → 本地不存任何向量，文档和向量都在阿里云百炼服务器上
 *
 * ══════════════════════════════════════════════════════════════
 * 三、使用前提
 * ══════════════════════════════════════════════════════════════
 *
 *   1. 登录阿里云百炼控制台：https://bailian.console.aliyun.com
 *   2. 创建知识库，命名为"恋爱大师"（必须和下面 KNOWLEDGE_INDEX 完全一致）
 *   3. 上传恋爱相关文档到该知识库
 *   4. application-local.yml 配好 spring.ai.dashscope.api-key
 *
 * ══════════════════════════════════════════════════════════════
 * 四、@Bean 单例 vs 每次临时 build 的区别
 * ══════════════════════════════════════════════════════════════
 *
 *   本地版 doChatWithLocalRag 里每次调用都 new 一个 RetrievalAugmentationAdvisor：
 *     → 每次 new，每次销毁，轻量对象，影响小
 *
 *   本类把 Advisor 注册成 @Bean 单例：
 *     → 全局只创建一次（含 DashScopeApi 客户端和网络连接配置）
 *     → 节省资源，可在多个地方复用
 */
@Configuration
@Slf4j
public class DashScopeRagAdvisorConfig {

    /**
     * 从 application-local.yml 读取 DashScope api-key
     * 同一个 key 同时用于：聊天 + Embedding + 知识库检索（同一个阿里云账号）
     */
    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建阿里云知识库版 RAG Advisor Bean（单例，启动时创建一次）
     *
     * 用法：在 LoveApp.doChatWithDashScopeRag 里 .advisors(dashScopeRagAdvisor)
     * 触发时机：每次 .call() 的 BEFORE 阶段，把用户问题发给百炼知识库检索，
     *           把检索结果文档拼进 Prompt
     */
    @Bean
    public Advisor dashScopeRagAdvisor() {
        log.info("[DashScopeRagAdvisor] ★ Bean 开始创建，apiKey前8位={}", dashScopeApiKey.substring(0, 8));

        // ── 第1步：创建 DashScope API 客户端 ──────────────────────────
        // DashScopeApi = 阿里云百炼平台的 HTTP 客户端封装
        // apiKey 是身份认证凭证，后续所有请求都带这个 key
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();

        // ── 第2步：指定要检索的知识库名称 ──────────────────────────────
        // 必须和阿里云百炼控制台上创建的知识库名字完全一致
        // 换知识库只改这一行字符串
        final String KNOWLEDGE_INDEX = "恋爱大师";

        // ── 第3步：创建云端文档检索器 ──────────────────────────────────
        // DashScopeDocumentRetriever：接收问题 → 去云端"恋爱大师"知识库检索
        // 向量化 + 搜索全在云端完成，本地只发问题、收文档段落
        DocumentRetriever dashScopeDocumentRetriever = new DashScopeDocumentRetriever(
                dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(KNOWLEDGE_INDEX)
                        .build()
        );

        // ── 第4步：组装 RAG Advisor ────────────────────────────────────
        // RetrievalAugmentationAdvisor = Spring AI 的 RAG 核心拦截器
        // 把云端检索器装进去，BEFORE 阶段会检索并把文档拼进 Prompt
        Advisor advisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(dashScopeDocumentRetriever)
                .build();
        log.info("[DashScopeRagAdvisor] ★ Bean 创建完成，内部检索器类型={}", dashScopeDocumentRetriever.getClass().getName());
        return advisor;
    }
}

package com.yupi.yuaiagent.rag.query;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 查询重写器 — 把用户口语化问题改写成更适合向量搜索的专业表达
 *
 * 场景举例：
 *   用户原问："她总是不回我消息，咋整"
 *   改写后：  "恋爱中对方长时间不回复消息，如何处理和沟通？"
 *
 * 改写后再去向量数据库搜，能命中更多相关文档，回答质量更高。
 */
@Component
public class QueryRewriter {

    private final QueryTransformer queryTransformer;

    public QueryRewriter(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(dashScopeChatModel))
                .build();
    }

    public String doQueryRewrite(String prompt) {
        Query transformedQuery = queryTransformer.transform(new Query(prompt));
        System.out.println("[QueryRewriter] 原始: " + prompt + " → 改写: " + transformedQuery.text());
        return transformedQuery.text();
    }
}

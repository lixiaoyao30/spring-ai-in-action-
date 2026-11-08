package com.yupi.yuaiagent.demo.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多查询扩展演示
 *
 * 作用：把用户的提问1个问题，用 AI 扩展成多个不同角度的问题，
 *       分别去向量库搜索，搜出的文档更全面，AI 回答更准确。
 *
 * 使用场景：知识库大、用户问题角度单一时使用。
 * 注意：每次扩展都会调 AI，numberOfQueries=3 就是 3 次额外调用，成本较高，慎用。
 */
@Component
public class MultiQueryExpanderDemo {

    // 容器里有 dashScopeChatModel 和 openAiChatModel 两个 ChatModel，必须指定名字
    // @Resource(name="xxx") 按名字精确取，Bean 名由 spring-ai-alibaba-starter 固定为 dashScopeChatModel
    @Resource(name = "dashScopeChatModel")
    private ChatModel dashScopeChatModel;

    /**
     * 把一个查询扩展成多个语义相关的子查询
     *
     * @param query 用户原始问题
     * @return 扩展后的 Query 列表（包含原始问题 + 3个扩展问题，共4个）
     */
    public List<Query> expand(String query) {
        MultiQueryExpander queryExpander = buildExpander();
        List<Query> queries = queryExpander.expand(new Query(query));

        System.out.println("======== 多查询扩展结果 ========");
        System.out.println("原始问题：" + query);
        System.out.println("扩展后共 " + queries.size() + " 个问题：");
        for (int i = 0; i < queries.size(); i++) {
            System.out.println("  [" + (i + 1) + "] " + queries.get(i).text());
        }
        System.out.println("================================");

        return queries;
    }

    public MultiQueryExpander buildExpander() {
        return MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(dashScopeChatModel))
                .numberOfQueries(3)
                .build();
    }
}

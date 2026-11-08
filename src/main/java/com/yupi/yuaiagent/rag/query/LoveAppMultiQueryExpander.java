package com.yupi.yuaiagent.rag.query;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.stereotype.Component;

/**
 * 多查询扩展器 — 把1个问题扩展成多个不同角度的子问题，分别搜索后合并结果

 * 在多轮会话场⁠景中，用户输入的提示词‌有时可能不够完整，或者存在​歧义。多查询扩展技‎术可以扩大检索范围，提‌高相关文档的召回率。
 * 使用多查询扩展时，要注意：
 * 设置合适的查询数量（建议 3 - 5 个），过多会影响性能、增大成本
 * 保留原始查询的核心语义
 *
 * 场景举例：
 *   原始问题："如何提升单身人士的个人吸引力？"
 *   扩展后：
 *     [1] 如何提升单身人士的个人吸引力？
 *     [2] 单身状态下提高自身魅力的有效方法有哪些？
 *     [3] 单身人士如何通过改善外在形象和内在气质吸引异性？
 *     [4] 提升单身人士社交吸引力的实用技巧有哪些？
 *
 * 注意：numberOfQueries=3 表示额外扩展3个，加上原始问题共4个，每个都会调一次向量搜索。
 *
 * 多查询扩展的完整使用流程可以包括三个步骤：
 * 使用扩展后的查询召回文档：遍历扩展后的查询列表，对每个查询使用 DocumentRetriever 来召回相关文档。
 * 整合召回的文档：将每个查询召回的文档进行整合，形成一个包含所有相关信息的文档集合。（也可以使用 文档合并器 去重）
 * 使用召回的文档改写 Prompt：将整合后的文档内容添加到原始 Prompt 中，为大语言模型提供更丰富的上下文信息。
 * 💡需要注意，多查询扩展会‌增加查询次数和计算成本，效果也不易量化评估，所以个人建‌议慎用这种优化方式。
 */
@Component
public class LoveAppMultiQueryExpander {

    @Resource(name = "dashScopeChatModel")
    private ChatModel dashScopeChatModel;

    /**
     * 构建 MultiQueryExpander 实例，供 RetrievalAugmentationAdvisor 使用
     * 框架拿到这个对象后，会在检索阶段自动调用扩展，无需手动调用
     */
    public MultiQueryExpander buildExpander() {
        return MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(dashScopeChatModel))
                .numberOfQueries(3)
                .build();
    }
}

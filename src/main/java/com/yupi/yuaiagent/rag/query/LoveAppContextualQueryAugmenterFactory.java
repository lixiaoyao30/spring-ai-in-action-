package com.yupi.yuaiagent.rag.query;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

/**
 * 上下文查询增强器工厂
 *
 * 作用：控制"向量库搜不到相关文档时"AI 怎么回答。
 *
 * allowEmptyContext(false) + 自定义提示词：
 *   搜不到文档 → AI 不自由发挥，而是回复固定的引导话术
 *   适合：垂直领域助手（恋爱顾问只回答恋爱问题，搜不到就礼貌拒绝）
 *
 * 对比 allowEmptyContext(true)：
 *   搜不到文档 → AI 凭自身训练数据回答（可能答偏）
 *   适合：通用助手
 *
 * 调用入口：LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor()
 *   把 createInstance() 的返回值传给 .queryAugmenter(...)
 */
public class LoveAppContextualQueryAugmenterFactory {

    /**
     * 创建自定义上下文查询增强器
     * 搜不到文档时，返回固定的中文引导话术，而不是让 AI 自由发挥
     */
    public static ContextualQueryAugmenter createInstance() {
        // 搜不到文档时展示给用户的提示语
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                你应该输出下面的内容：
                抱歉，我只能回答恋爱相关的问题，别的没办法帮到您哦。
                有问题可以联系编程导航客服 https://codefather.cn
                """);

        return ContextualQueryAugmenter.builder()
                // false = 搜不到文档时不让 AI 自由发挥，走 emptyContextPromptTemplate
                .allowEmptyContext(false)
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .build();
    }
}

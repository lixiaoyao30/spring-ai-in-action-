package com.yupi.yuaiagent.app;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LoveApp 的 Spring 配置类
 *
 * 为什么需要这个类？
 *   LoveApp 没有 @Component，Spring 不会自动创建它。
 *   这里用 @Bean 手动告诉 Spring："请帮我创建一个 LoveApp 放进容器"，
 *   这样 AiController 里的 @Resource private LoveApp loveApp 才能注入成功。
 *
 * 为什么用内存版（只传 dashscopeChatModel，不传 redisTemplate）？
 *   内存版最简单：零外部依赖，本地不需要 Redis/MySQL 在线就能跑。
 *   对话记忆只保存在 JVM 内存里，重启后丢失，适合本地开发和演示。
 *   生产环境若需要对话持久化，改为 new LoveApp(chatModel, redisTemplate) 即可。
 */
@Configuration
public class LoveAppConfig {

    /**
     * 创建 LoveApp Bean（内存版，对话记忆存 JVM 内存，重启丢失）
     *
     * @param dashscopeChatModel 通义千问模型，由 spring-ai-alibaba-starter 自动注册
     * @return LoveApp 实例，Spring 会把它放进容器供注入
     */
    @Bean
    public LoveApp loveApp(@Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        return new LoveApp(openAiChatModel);
    }
}

package com.yupi.yuaiagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════
 * Redis 版对话记忆存储
 * ═══════════════════════════════════════════════════════
 *
 * 【为什么需要它？】
 *   Spring AI 内置的 InMemoryChatMemoryRepository 存在 JVM 内存里，
 *   服务重启就丢失。Redis 是独立进程，重启 Java 服务不影响 Redis 数据。
 *
 * 【它是怎么工作的？接口约定】
 *
 *   ChatMemoryRepository（接口 = 合同）
 *        │  规定必须实现 4 个方法
 *        │
 *   RedisChatMemoryRepository（本类 = 合同的 Redis 实现）
 *        ├── findByConversationId()  → 查历史（读 Redis）
 *        ├── saveAll()               → 存本轮对话（写 Redis）
 *        ├── findConversationIds()   → 查所有会话 ID（读 Redis）
 *        └── deleteByConversationId() → 删某会话（删 Redis Key）
 *
 *   框架（MessageChatMemoryAdvisor）只认识接口，不管底层是 Redis 还是 MySQL，
 *   只要实现了接口，框架都能调用。这就是接口的意义。
 *
 * 【Redis 里数据长什么样？】
 *
 *   chat_memory:ids              [Set - 存所有会话ID，用于列举]
 *     └── "redis-test", "user-A", "memory-test", ...
 *
 *   chat_memory:redis-test       [List - 存该会话的消息，有顺序]
 *     ├── {"role":"user",      "content":"你好，我叫鱼皮"}
 *     ├── {"role":"assistant", "content":"你好鱼皮！..."}
 *     └── {"role":"user",      "content":"我最近暗恋一个人"}
 *
 * 【验证 Redis 里的数据（终端命令）】
 *   redis-cli --raw LRANGE chat_memory:redis-test 0 -1
 *   redis-cli SMEMBERS chat_memory:ids
 *   （--raw 必须加，否则中文显示为乱码 \xe4\xb8\xad...）
 */
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private final StringRedisTemplate redisTemplate; // Spring 封装的 Redis 客户端
    private final ObjectMapper objectMapper = new ObjectMapper(); // Jackson：Java 对象 ↔ JSON 字符串

    private final String keyPrefix;  // Redis Key 前缀，默认 "chat_memory:"，防止多项目 Key 冲突
    private final String idsSetKey;  // 存所有会话 ID 的 Set Key = keyPrefix + "ids"
    private final Duration ttl;      // 过期时间：null=永不过期；生产建议设置，防 Redis 内存撑爆

    // ── 构造方法：提供 3 个版本，按需选用 ──────────────────────────

    /** 最简版：默认前缀，永不过期。适合本地开发/测试 */
    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        this(redisTemplate, "chat_memory:", null);
    }

    /** 带过期版：默认前缀 + TTL。适合生产环境（推荐） */
    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, Duration ttl) {
        this(redisTemplate, "chat_memory:", ttl);
    }

    /** 完整版：自定义前缀 + TTL。同一 Redis 部署多个项目时用 */
    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate,
                                     String keyPrefix, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix     = keyPrefix;
        this.idsSetKey     = keyPrefix + "ids"; // 例："chat_memory:ids"
        this.ttl           = ttl;
    }

    // ══════════════════════════════════════════════════════════
    // ① findByConversationId — 查历史消息
    //
    // 调用时机：MessageChatMemoryAdvisor.before()（发给 AI 之前）
    // 目的：把历史消息拼到请求里，让 AI 能"记住"之前说了什么
    //
    // Redis 命令：LRANGE chat_memory:{id} 0 -1
    //   0 -1 = 从第 0 条到最后一条（取全部）
    // ══════════════════════════════════════════════════════════
    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = keyPrefix + conversationId; // 例："chat_memory:redis-test"

        // LRANGE → 返回 JSON 字符串列表，Key 不存在时返回 null
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);

        List<Message> messages = new ArrayList<>();
        if (jsonList == null) return messages; // Key 不存在 = 该会话没有历史，返回空列表

        for (String json : jsonList) {
            // json 示例：{"role":"user","content":"你好，我叫鱼皮"}
            try {
                // JSON 字符串 → Map（字典）
                // TypeReference<>(){} 是 Jackson 的固定写法，告诉它解析成 Map<String,String>
                Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {});

                String role    = map.get("role");    // "user" / "assistant" / "system"
                String content = map.get("content"); // 消息正文

                // 根据 role 创建对应的 Message 子类（switch 表达式，Java 14+）
                Message message = switch (role) {
                    case "user"      -> new UserMessage(content);
                    case "assistant" -> new AssistantMessage(content);
                    case "system"    -> new SystemMessage(content);
                    default          -> new UserMessage(content); // 未知 role 当 user 处理
                };
                messages.add(message);
            } catch (Exception e) {
                // 单条解析失败不影响其他消息，打日志跳过
                log.warn("[RedisChatMemory] 解析消息失败，跳过: json={}", json, e);
            }
        }
        return messages;
    }

    // ══════════════════════════════════════════════════════════
    // ② saveAll — 保存本轮所有消息
    //
    // 调用时机：MessageChatMemoryAdvisor.after()（AI 响应返回后）
    // 参数 messages：MessageWindowChatMemory 已截断到 maxMessages 条的【全量】列表
    //
    // 策略：先删后插（不是追加！）
    //   原因：MessageWindowChatMemory 会截断旧消息（保留最近20条）
    //        如果直接 RPUSH 追加，Redis 里会越来越多，与内存不一致
    //        先 DEL 再 RPUSH，Redis 始终和内存里保持完全一致
    //
    // Redis 命令序列：
    //   DEL   chat_memory:redis-test
    //   RPUSH chat_memory:redis-test '{"role":"user","content":"..."}'
    //   RPUSH chat_memory:redis-test '{"role":"assistant","content":"..."}'
    //   SADD  chat_memory:ids redis-test
    //   EXPIRE chat_memory:redis-test 604800   （如果设了 ttl）
    // ══════════════════════════════════════════════════════════
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = keyPrefix + conversationId;

        redisTemplate.delete(key); // 先删旧数据（DEL 命令）

        for (Message message : messages) {
            try {
                // Message 对象 → Map → JSON 字符串
                Map<String, String> map = new HashMap<>();
                map.put("role",    message.getMessageType().getValue()); // 枚举 → "user"/"assistant"/"system"
                map.put("content", message.getText());                   // 消息正文

                String json = objectMapper.writeValueAsString(map);
                // 追加到 List 尾部（RPUSH），保持消息时间顺序
                redisTemplate.opsForList().rightPush(key, json);
            } catch (Exception e) {
                log.error("[RedisChatMemory] 序列化消息失败，跳过: message={}", message, e);
            }
        }

        // 把会话 ID 加入全局 Set（Set 自动去重，多次 SADD 同一个值无副作用）
        redisTemplate.opsForSet().add(idsSetKey, conversationId);

        // 刷新过期时间：每次有新消息都重置计时器，实现"N天无活动则自动删除"
        if (ttl != null) {
            redisTemplate.expire(key, ttl); // EXPIRE key 604800（秒）
        }

        log.debug("[RedisChatMemory] saved {} messages, conversationId={}, ttl={}",
                messages.size(), conversationId, ttl);
    }

    // ══════════════════════════════════════════════════════════
    // ③ findConversationIds — 查所有会话 ID
    //
    // Redis 命令：SMEMBERS chat_memory:ids
    // 使用场景：管理后台列出所有用户的会话、批量清理历史等
    // ══════════════════════════════════════════════════════════
    @Override
    public List<String> findConversationIds() {
        Set<String> ids = redisTemplate.opsForSet().members(idsSetKey);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    // ══════════════════════════════════════════════════════════
    // ④ deleteByConversationId — 删除某会话的所有记忆
    //
    // Redis 命令：
    //   DEL  chat_memory:redis-test    （删消息 List）
    //   SREM chat_memory:ids redis-test （从 ID Set 里移除）
    // 使用场景：用户主动清空对话历史、注销账号时清理数据
    // ══════════════════════════════════════════════════════════
    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(keyPrefix + conversationId);             // 删消息
        redisTemplate.opsForSet().remove(idsSetKey, conversationId);  // 删 ID 记录
        log.debug("[RedisChatMemory] deleted conversationId={}", conversationId);
    }
}

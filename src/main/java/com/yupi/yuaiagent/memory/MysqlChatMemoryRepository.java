package com.yupi.yuaiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ┌─────────────────────────────────────────────────────────────┐
 * │              MySQL 版对话记忆存储（需要审计时使用）              │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 【为什么需要它？】
 *   Redis 虽然快，但数据是 Key-Value 结构，不方便做统计分析。
 *   MySQL 可以用 SQL 查询：某用户说了多少轮、哪些词出现频率最高、
 *   导出对话日志给运营等——需要这类功能时选 MySQL。
 *
 * 【MySQL vs Redis 怎么选？】
 *   ✅ 优先选 Redis：绝大多数业务场景，性能高，自带 TTL 自动清理
 *   ✅ 选 MySQL：需要对话记录审计、数据分析、合规存档等场景
 *   ❌ 不要两个都用：同一个对话存两遍，数据会不一致
 *
 * 【使用前必须建表（只需执行一次）】
 *
 *   CREATE TABLE chat_memory (
 *       id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
 *       conversation_id VARCHAR(64)  NOT NULL COMMENT '会话 ID，对应 chatId',
 *       role            VARCHAR(16)  NOT NULL COMMENT 'user / assistant / system',
 *       content         TEXT         NOT NULL COMMENT '消息正文',
 *       created_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
 *       INDEX idx_conversation_id (conversation_id)   -- 按会话查询的索引，必须加
 *   );
 *
 * 【使用方式 - 在 LoveApp 里切换】
 *   // 注入 JdbcTemplate（Spring Boot + spring-boot-starter-data-jdbc 自动装配）
 *   @Autowired JdbcTemplate jdbcTemplate;
 *
 *   // 构建时传入，使用默认表名 "chat_memory"
 *   new MysqlChatMemoryRepository(jdbcTemplate)
 *
 *   // 或者自定义表名（多租户/多项目共用同一个 MySQL 时隔离）
 *   new MysqlChatMemoryRepository(jdbcTemplate, "project_a_chat_memory")
 *
 * 【application.yml 需要配置数据库连接】
 *   spring:
 *     datasource:
 *       url: jdbc:mysql://localhost:3306/your_db?useUnicode=true&characterEncoding=utf8
 *       username: root
 *       password: your_password
 *       driver-class-name: com.mysql.cj.jdbc.Driver
 */
@Slf4j
public class MysqlChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    // 表名可自定义，默认 "chat_memory"
    // 好处：多个项目/租户使用同一个 MySQL 时，只需改表名就能隔离，不改逻辑代码
    private final String tableName;

    // ── 构造方法（提供两个版本）──────────────────────────────────

    /**
     * 最简构造：使用默认表名 "chat_memory"
     */
    public MysqlChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "chat_memory");
    }

    /**
     * 自定义表名构造
     *
     * @param tableName 实际存储的表名，例如 "project_a_chat_memory"
     */
    public MysqlChatMemoryRepository(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName    = tableName;
    }

    // ── ① 查所有会话 ID ──────────────────────────────────────────
    // DISTINCT 去重，因为同一个会话会有多条记录
    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT conversation_id FROM " + tableName,
                String.class
        );
    }

    // ── ② 按会话 ID 查历史消息 ───────────────────────────────────
    // ORDER BY id ASC：按插入顺序还原消息，保证对话顺序正确
    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, content FROM " + tableName
                        + " WHERE conversation_id = ? ORDER BY id ASC",
                conversationId
        );

        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String role    = (String) row.get("role");
            String content = (String) row.get("content");
            // 根据 role 字段还原成对应的 Message 子类
            Message message = switch (role) {
                case "user"      -> new UserMessage(content);
                case "assistant" -> new AssistantMessage(content);
                case "system"    -> new SystemMessage(content);
                default          -> new UserMessage(content);
            };
            messages.add(message);
        }
        return messages;
    }

    // ── ③ 保存本轮所有消息 ───────────────────────────────────────
    // Spring AI 每次对话结束后调用此方法，传入该会话的【全量】消息列表。
    // 采用"先删后插"策略：先清空旧数据，再整体重写。
    // 这样和内存里的状态永远保持一致，不会出现重复或遗漏。
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 先删旧数据
        jdbcTemplate.update(
                "DELETE FROM " + tableName + " WHERE conversation_id = ?",
                conversationId
        );
        // 再逐条插入新数据
        for (Message message : messages) {
            String role = message.getMessageType().getValue(); // "user" / "assistant" / "system"
            jdbcTemplate.update(
                    "INSERT INTO " + tableName + " (conversation_id, role, content) VALUES (?, ?, ?)",
                    conversationId, role, message.getText()
            );
        }
        log.debug("[MysqlChatMemory] saved {} messages, conversationId={}",
                messages.size(), conversationId);
    }

    // ── ④ 清空某会话的记忆 ───────────────────────────────────────
    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update(
                "DELETE FROM " + tableName + " WHERE conversation_id = ?",
                conversationId
        );
        log.debug("[MysqlChatMemory] deleted conversationId={}", conversationId);
    }
}

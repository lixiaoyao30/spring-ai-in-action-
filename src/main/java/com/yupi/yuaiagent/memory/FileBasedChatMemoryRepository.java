package com.yupi.yuaiagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════
 * 文件版对话记忆存储（学习 / 本地离线场景）
 * ═══════════════════════════════════════════════════════
 *
 * 【为什么需要它？三种存储方式对比】
 *
 *   InMemoryChatMemoryRepository  → 存 JVM 内存，重启丢失，只适合演示
 *   RedisChatMemoryRepository     → 需要启动 Redis 服务，有外部依赖
 *   MysqlChatMemoryRepository     → 需要启动 MySQL 服务，有外部依赖
 *   FileBasedChatMemoryRepository → 零外部依赖，JSON 文件存磁盘，重启不丢 ← 本类
 *
 *   适合场景：学习调试、离线演示、CI 自动化测试、嵌入式/单机部署
 *   不适合场景：高并发（多线程写同一文件会冲突）、分布式（文件只在本机）
 *
 * 【文件存储结构】
 *
 *   chat_memory/                       ← 默认存储目录（可自定义）
 *     ├── redis-test.json              ← 每个会话一个文件
 *     ├── mysql-test.json              ← 文件名 = conversationId（即 chatId）
 *     └── memory-test.json
 *
 *   每个 JSON 文件内容（消息数组，按时间顺序）：
 *   [
 *     {"role": "user",      "content": "你好，我叫鱼皮"},
 *     {"role": "assistant", "content": "你好鱼皮！很高兴认识你..."},
 *     {"role": "user",      "content": "我叫什么名字？"},
 *     {"role": "assistant", "content": "你叫鱼皮"}
 *   ]
 *
 * 【调用时机】（和 Redis / MySQL 版完全相同，框架自动调用）
 *
 *   你只调 doChat()，内部 .call() 是扳机，Spring AI 框架自动执行：
 *
 *   before 阶段（发给 AI 之前）：
 *     MessageChatMemoryAdvisor.before()
 *       └─ findByConversationId(chatId)  → 读 JSON 文件，取历史消息注入请求
 *
 *   after 阶段（AI 响应返回后）：
 *     MessageChatMemoryAdvisor.after()
 *       └─ saveAll(chatId, messages)     → 覆盖写 JSON 文件，持久化本轮对话
 *
 * 【使用方式 - 在 LoveApp 里只改一行】
 *
 *   MessageWindowChatMemory.builder()
 *       .chatMemoryRepository(new FileBasedChatMemoryRepository())  // ← 换这里
 *       .maxMessages(20)
 *       .build()
 *
 * 【验证文件内容（终端命令）】
 *   cat chat_memory/redis-test.json       # 查看某会话的历史
 *   ls chat_memory/                        # 列出所有会话文件
 */
@Slf4j
public class FileBasedChatMemoryRepository implements ChatMemoryRepository {

    // Jackson：Java 对象 ↔ JSON 字符串
    // INDENT_OUTPUT = 写入文件时格式化缩进，人眼可读，方便调试
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // 所有会话 JSON 文件的存储目录
    private final Path storageDir;

    // ── 构造方法 ─────────────────────────────────────────────────

    /**
     * 默认构造：文件存到当前工作目录下的 chat_memory/ 文件夹
     * 项目根目录启动时 = 项目根目录/chat_memory/
     */
    public FileBasedChatMemoryRepository() {
        this(Paths.get("chat_memory"));
    }

    /**
     * 指定目录构造
     * @param storageDir 存储目录路径，例如 "/data/chat_memory" 或 "./my_chats"
     */
    public FileBasedChatMemoryRepository(String storageDir) {
        this(Paths.get(storageDir));
    }

    /**
     * Path 构造（内部统一入口）
     * 目录不存在时自动创建，避免第一次使用时报 NoSuchFileException
     */
    public FileBasedChatMemoryRepository(Path storageDir) {
        this.storageDir = storageDir;
        try {
            // createDirectories：目录已存在时静默跳过，不会报错
            Files.createDirectories(storageDir);
            log.info("[FileMemory] 存储目录已就绪: {}", storageDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("[FileMemory] 无法创建存储目录: " + storageDir, e);
        }
    }

    // ══════════════════════════════════════════════════════════
    // ① findByConversationId — 读文件，取历史消息
    //
    // 调用时机：MessageChatMemoryAdvisor.before()（.call() 触发，发给 AI 之前）
    // 目的：读取磁盘上的历史记录，注入到请求里，AI 才能"记住"上轮内容
    //
    // 文件操作：读 storageDir/{conversationId}.json
    //   文件不存在（第一次对话）→ 返回空列表，正常，不报错
    //   文件存在 → 解析 JSON 数组 → 转成 Message 对象列表
    // ══════════════════════════════════════════════════════════
    @Override
    public List<Message> findByConversationId(String conversationId) {
        Path file = resolveFile(conversationId);

        // 文件不存在 = 该会话第一次对话，没有历史，直接返回空列表
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        try {
            // 读文件 → List<Map>（每个 Map 是一条消息：{"role":"user","content":"..."}）
            List<Map<String, String>> rawList = objectMapper.readValue(
                    file.toFile(),
                    new TypeReference<>() {}
                    // TypeReference<>(){} 是 Jackson 保留泛型类型信息的固定写法
            );

            List<Message> messages = new ArrayList<>();
            for (Map<String, String> raw : rawList) {
                String role    = raw.get("role");    // "user" / "assistant" / "system"
                String content = raw.get("content"); // 消息正文

                // 根据 role 创建不同的 Message 子类（框架需要具体子类，不能用父类）
                Message message = switch (role) {
                    case "user"      -> new UserMessage(content);
                    case "assistant" -> new AssistantMessage(content);
                    case "system"    -> new SystemMessage(content);
                    default          -> new UserMessage(content); // 未知 role 按 user 处理
                };
                messages.add(message);
            }
            log.debug("[FileMemory] loaded {} messages from {}", messages.size(), file.getFileName());
            return messages;

        } catch (IOException e) {
            // 文件损坏或 JSON 格式错误：打日志，返回空列表（不崩溃，下次 saveAll 会覆盖修复）
            log.warn("[FileMemory] 读取文件失败，返回空历史: file={}", file, e);
            return new ArrayList<>();
        }
    }

    // ══════════════════════════════════════════════════════════
    // ② saveAll — 覆盖写文件，持久化本轮所有消息
    //
    // 调用时机：MessageChatMemoryAdvisor.after()（AI 响应返回后）
    // 参数 messages：MessageWindowChatMemory 已截断到 maxMessages 条的【全量】列表
    //
    // 策略：覆盖写（不是追加）
    //   理由：MessageWindowChatMemory 传来的是截断后的完整列表
    //   直接覆盖 = 文件内容始终和内存里保持一致
    //   如果用追加，文件会越来越大，超出 maxMessages 的旧消息永远删不掉
    //
    // 文件操作：将消息列表序列化为格式化 JSON → 覆盖写入 storageDir/{conversationId}.json
    // ══════════════════════════════════════════════════════════
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Path file = resolveFile(conversationId);

        // 把 Message 对象列表转成 List<Map>，方便 JSON 序列化
        List<Map<String, String>> rawList = messages.stream()
                .map(msg -> {
                    Map<String, String> map = new HashMap<>();
                    // getMessageType().getValue() 把枚举转成字符串："user"/"assistant"/"system"
                    map.put("role",    msg.getMessageType().getValue());
                    map.put("content", msg.getText());
                    return map;
                })
                .collect(Collectors.toList());

        try {
            // writeValue：序列化成格式化 JSON 并写入文件（文件不存在时自动创建，存在时覆盖）
            objectMapper.writeValue(file.toFile(), rawList);
            log.debug("[FileMemory] saved {} messages → {}", messages.size(), file.getFileName());
        } catch (IOException e) {
            log.error("[FileMemory] 写入文件失败: file={}", file, e);
        }
    }

    // ══════════════════════════════════════════════════════════
    // ③ findConversationIds — 列出所有会话 ID
    //
    // 实现：扫描 storageDir 下所有 .json 文件，去掉后缀即为 conversationId
    // 使用场景：管理后台列出所有会话、批量清理历史等（框架不自动调用，手动使用）
    // ══════════════════════════════════════════════════════════
    @Override
    public List<String> findConversationIds() {
        try (var stream = Files.list(storageDir)) {
            // Files.list 返回 Stream，必须用 try-with-resources 关闭，否则文件句柄泄漏
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[FileMemory] 列出会话 ID 失败: dir={}", storageDir, e);
            return new ArrayList<>();
        }
    }

    // ══════════════════════════════════════════════════════════
    // ④ deleteByConversationId — 删除某会话的历史文件
    //
    // 文件操作：删除 storageDir/{conversationId}.json
    // 使用场景：用户主动清空对话历史、测试前清理旧数据（框架不自动调用，手动使用）
    // ══════════════════════════════════════════════════════════
    @Override
    public void deleteByConversationId(String conversationId) {
        Path file = resolveFile(conversationId);
        try {
            // deleteIfExists：文件不存在时静默跳过，不抛异常，安全
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.debug("[FileMemory] deleted: {}", file.getFileName());
            }
        } catch (IOException e) {
            log.error("[FileMemory] 删除文件失败: file={}", file, e);
        }
    }

    // ── 私有工具方法 ──────────────────────────────────────────────

    /**
     * 根据 conversationId 拼出完整文件路径
     *
     * 例：conversationId = "redis-test"
     *     storageDir     = chat_memory/
     *     返回            = chat_memory/redis-test.json
     *
     * 安全处理：去掉 conversationId 里的 / 和 \，防止目录穿越攻击
     *   例：conversationId = "../secret"
     *       处理后          = "..secret.json"（留在 storageDir 内，无法越级）
     */
    private Path resolveFile(String conversationId) {
        String safeName = conversationId.replaceAll("[/\\\\]", "_");
        return storageDir.resolve(safeName + ".json");
    }
}

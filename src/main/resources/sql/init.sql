-- ═══════════════════════════════════════════════════════════════
-- 数据库初始化脚本（首次部署时手动执行一次即可）
--
-- 第一步：创建数据库
--   在 MySQL 控制台（或 DataGrip / Navicat）粘贴并执行本文件
--   执行完后 application.yml 里的 spring.datasource.url 就能正常连接
--
-- 第二步：刷新 IDEA 右侧 Database 面板，即可看到新建的表
-- ═══════════════════════════════════════════════════════════════

-- ① 创建数据库（已存在时跳过，不报错）
CREATE DATABASE IF NOT EXISTS yupi
    DEFAULT CHARACTER SET utf8mb4       -- 支持中文和 emoji
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- ② 切换到该数据库
USE yupi;

-- ③ 创建对话记忆表（已存在时跳过，不报错）
--
--   conversation_id : 会话 ID，对应 LoveApp.doChat() 的 chatId 参数
--   role            : 消息角色  user / assistant / system
--   content         : 消息正文（TEXT 支持长文本）
--   created_time    : 插入时间，自动填写，方便后续按时间范围查询
--
CREATE TABLE IF NOT EXISTS chat_memory (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话 ID，对应 chatId',
    role            VARCHAR(16)  NOT NULL COMMENT 'user / assistant / system',
    content         TEXT         NOT NULL COMMENT '消息正文',
    created_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)  -- 按会话查询的索引，必须加
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话记忆表';

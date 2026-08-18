CREATE DATABASE IF NOT EXISTS ai_knowledge_hub
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_knowledge_hub;

-- 知识库表
CREATE TABLE IF NOT EXISTS `kb_knowledge_base` (
                                                   `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                                                   `name`        VARCHAR(64)  NOT NULL                COMMENT '知识库名称',
    `description` VARCHAR(255) DEFAULT ''              COMMENT '描述',
    `visibility`  TINYINT      NOT NULL DEFAULT 0      COMMENT '0私有 1公开',
    `doc_count`   INT          NOT NULL DEFAULT 0      COMMENT '文档数',
    `create_by`   VARCHAR(64)  DEFAULT 'system'        COMMENT '创建人',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_name` (`name`),
    KEY `idx_create_time` (`create_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

-- 文档表
CREATE TABLE IF NOT EXISTS `kb_document` (
                                             `id`           BIGINT       NOT NULL AUTO_INCREMENT,
                                             `kb_id`        BIGINT       NOT NULL               COMMENT '所属知识库 id',
                                             `file_name`    VARCHAR(255) NOT NULL               COMMENT '原始文件名',
    `file_path`    VARCHAR(512) DEFAULT ''             COMMENT '存储路径',
    `file_type`    VARCHAR(16)  DEFAULT ''             COMMENT 'txt/md/pdf',
    `file_size`    BIGINT       NOT NULL DEFAULT 0     COMMENT '字节数',
    `chunk_count`  INT          NOT NULL DEFAULT 0     COMMENT '切片数',
    `status`       TINYINT      NOT NULL DEFAULT 0     COMMENT '0待解析 1解析中 2成功 3失败',
    `error_msg`    VARCHAR(512) DEFAULT ''             COMMENT '失败原因',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_kb_id` (`kb_id`),
    KEY `idx_status` (`status`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档';

-- 对话记录表
CREATE TABLE IF NOT EXISTS `kb_chat_record` (
                                                `id`            BIGINT      NOT NULL AUTO_INCREMENT,
                                                `conversation_id` VARCHAR(64) NOT NULL             COMMENT '会话 id',
    `kb_id`         BIGINT      DEFAULT NULL           COMMENT 'RAG 时的知识库',
    `question`      TEXT        NOT NULL,
    `answer`        LONGTEXT,
    `prompt_tokens` INT         DEFAULT 0,
    `completion_tokens` INT     DEFAULT 0,
    `cost_ms`       BIGINT      DEFAULT 0              COMMENT '耗时毫秒',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conversation` (`conversation_id`),
    KEY `idx_create_time` (`create_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记录';
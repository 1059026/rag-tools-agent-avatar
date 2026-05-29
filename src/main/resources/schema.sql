-- 创建数据库（如尚未存在）
CREATE DATABASE IF NOT EXISTS demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE demo;

-- 创建 user 表（如尚未存在）
CREATE TABLE IF NOT EXISTS user (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    name     VARCHAR(50)  NOT NULL COMMENT '姓名',
    email    VARCHAR(100) COMMENT '邮箱',
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

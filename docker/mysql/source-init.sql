-- 源数据库初始化脚本

-- 创建测试数据库
CREATE DATABASE IF NOT EXISTS test;
USE test;

-- 创建用户表
CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建订单表
CREATE TABLE orders (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 插入测试数据
INSERT INTO users (username, email) VALUES
('alice', 'alice@example.com'),
('bob', 'bob@example.com'),
('charlie', 'charlie@example.com');

INSERT INTO orders (user_id, product_name, amount) VALUES
(1, 'Laptop', 999.99),
(2, 'Mouse', 29.99),
(3, 'Keyboard', 79.99);

-- 创建 CDC 偏移量存储表
CREATE TABLE cdc_offsets (
    id VARCHAR(255) PRIMARY KEY,
    offset_data TEXT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 创建 Low Watermark 存储表
CREATE TABLE cdc_low_watermarks (
    table_database VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    snapshot_id VARCHAR(255) NOT NULL,
    position_type VARCHAR(50) NOT NULL,
    position_value TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'Active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (table_database, table_name, snapshot_id)
);

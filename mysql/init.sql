CREATE DATABASE IF NOT EXISTS `user_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `review_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `inventory_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `order_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ==================================================================
-- review_db
-- ==================================================================
USE `review_db`;

CREATE TABLE IF NOT EXISTS reviews (
    id          VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    target_type VARCHAR(20)  NOT NULL,
    target_id   VARCHAR(64)  NOT NULL,
    rating      TINYINT      NOT NULL DEFAULT 0,
    text        TEXT         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Text with Emoji support',
    images      JSON         COMMENT 'Array of image URLs',
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ==================================================================
-- inventory_db
-- ==================================================================
USE `inventory_db`;

-- Daily inventory table
CREATE TABLE IF NOT EXISTS daily_inventory (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id          VARCHAR(64)    NOT NULL,
    inv_date        DATE           NOT NULL,
    total_qty       INT            NOT NULL DEFAULT 0,
    available_qty   INT            NOT NULL DEFAULT 0,
    locked_qty      INT            NOT NULL DEFAULT 0,
    sold_qty        INT            NOT NULL DEFAULT 0,
    price_currency  VARCHAR(3)     NOT NULL DEFAULT 'CNY',
    price_units     BIGINT         NOT NULL DEFAULT 0,
    price_nanos     INT            NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_date (sku_id, inv_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Inventory lock table (one order ↔ one lock)
CREATE TABLE IF NOT EXISTS inventory_lock (
    lock_id     VARCHAR(64)   PRIMARY KEY,
    order_id    VARCHAR(64)   NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'LOCKED',
    created_at  BIGINT        NOT NULL,
    expire_at   BIGINT        NOT NULL,
    UNIQUE KEY uk_order_id (order_id),
    INDEX idx_status_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Inventory lock item table
CREATE TABLE IF NOT EXISTS inventory_lock_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_id     VARCHAR(64)   NOT NULL,
    sku_id      VARCHAR(64)   NOT NULL,
    inv_date    DATE          NOT NULL,
    quantity    INT           NOT NULL,
    INDEX idx_lock_id (lock_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ==================================================================
-- order_db
-- ==================================================================
USE `order_db`;

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    id              VARCHAR(64)   PRIMARY KEY,
    order_no        VARCHAR(32)   NOT NULL UNIQUE,
    user_id         VARCHAR(64)   NOT NULL,
    status          VARCHAR(24)   NOT NULL DEFAULT 'PENDING_PAYMENT',
    total_currency  VARCHAR(3)    NOT NULL DEFAULT 'CNY',
    total_units     BIGINT        NOT NULL DEFAULT 0,
    total_nanos     INT           NOT NULL DEFAULT 0,
    contact_name    VARCHAR(64),
    contact_phone   VARCHAR(20),
    contact_email   VARCHAR(128),
    source_channel  VARCHAR(16),
    source_agent_id VARCHAR(64),
    source_session  VARCHAR(64),
    cancel_reason   VARCHAR(256),
    expire_at       BIGINT,
    created_at      BIGINT        NOT NULL,
    updated_at      BIGINT        NOT NULL,
    paid_at         BIGINT,
    cancelled_at    BIGINT,
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Order items table
CREATE TABLE IF NOT EXISTS order_items (
    id                VARCHAR(64) PRIMARY KEY,
    order_id          VARCHAR(64) NOT NULL,
    spu_id            VARCHAR(64) NOT NULL,
    sku_id            VARCHAR(64) NOT NULL,
    product_name      VARCHAR(256),
    sku_name          VARCHAR(256),
    item_date         DATE        NOT NULL,
    end_date          DATE,
    quantity          INT         NOT NULL DEFAULT 1,
    unit_price_ccy    VARCHAR(3),
    unit_price_units  BIGINT,
    unit_price_nanos  INT,
    subtotal_ccy      VARCHAR(3),
    subtotal_units    BIGINT,
    subtotal_nanos    INT,
    inv_lock_id       VARCHAR(64),
    INDEX idx_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
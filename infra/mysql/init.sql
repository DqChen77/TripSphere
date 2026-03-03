CREATE DATABASE IF NOT EXISTS `user_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `review_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `inventory_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `order_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ==================================================================
-- review_db (Go service, no JPA auto-DDL — tables must be created here)
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
-- inventory_db (JPA ddl-auto: update — tables auto-created by Hibernate)
-- ==================================================================

-- ==================================================================
-- order_db (JPA ddl-auto: update — tables auto-created by Hibernate)
-- ==================================================================
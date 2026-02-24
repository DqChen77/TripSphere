# TripSphere 商品订单域技术方案

## 1. 概述

### 1.1 背景

TripSphere 是一个 AI-Native 的在线旅游服务系统。现有信息展示层服务（景点、酒店、POI 等）已基本完成，
需要新增**商品 → 库存 → 订单**的交易能力，让 AI Agent 不仅能"读"，还能代用户"写"——完成查价、下单、
支付确认等闭环操作。

### 1.2 设计目标

- **三服务拆分**：Product / Inventory / Order 独立部署，通过 gRPC 通信
- **静态与动态分离**：Product 管理不变的商品规则，Inventory 管理按日变化的库存和价格
- **分布式事务**：通过库存锁定 → 确认/释放机制保障跨服务数据一致性
- **Agent 可调用**：所有 gRPC 接口可封装为 MCP Tools，供 Agent 编排调用

### 1.3 技术栈

| 层面 | 选型 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.5.x |
| 微服务 | Spring Cloud Alibaba + Nacos | 2025.0.x |
| RPC | gRPC + grpc-spring-boot-starter | 1.78.0 / 3.1.0.RELEASE |
| 序列化 | Protobuf | 4.33.x |
| 对象映射 | MapStruct + protobuf-spi-impl | 1.6.3 |
| 代码规范 | Spotless (Google Java Format AOSP) | 2.43.0 |

### 1.4 数据库选型

| 服务 | 主存储 | 辅助存储 | 选型理由 |
|------|--------|----------|----------|
| trip-product-service | MongoDB | — | SPU/SKU 属性灵活多变，`Struct` 字段天然适合文档模型；与现有 poi/itinerary 等服务一致 |
| trip-inventory-service | MySQL | Redis | 库存扣减需要行级锁和事务保障，适合关系型；Redis 做热数据缓存和原子锁定操作 |
| trip-order-service | MySQL | Redis | 订单需要强事务一致性；Redis 用于订单超时关闭的延迟队列 |

---

## 2. 领域模型

### 2.1 三服务职责划分

```
┌────────────────────────┐  ┌──────────────────────────┐  ┌────────────────────────┐
│  trip-product-service   │  │  trip-inventory-service   │  │  trip-order-service     │
│  ════════════════════   │  │  ══════════════════════   │  │  ════════════════════   │
│                         │  │                           │  │                         │
│  • SPU 定义与 CRUD      │  │  • 日历库存管理            │  │  • 订单生命周期          │
│  • SKU 定义与 CRUD      │  │  • 日历价格管理            │  │  • 订单创建 (Saga 编排)  │
│  • 基础价格 (参考价)     │  │  • 库存锁定 / 确认 / 释放 │  │  • 支付确认 (模拟)       │
│  • 商品上下架状态       │  │  • 可用性查询              │  │  • 订单取消与回滚        │
│  • 灵活属性 (Struct)    │  │  • 锁定超时自动释放        │  │  • 订单查询              │
│                         │  │                           │  │                         │
│  数据特征: 静态、低频写  │  │  数据特征: 动态、高频读写   │  │  数据特征: 事务性、状态机 │
│  存储: MongoDB           │  │  存储: MySQL + Redis       │  │  存储: MySQL + Redis     │
└────────────────────────┘  └──────────────────────────┘  └────────────────────────┘
```

### 2.2 与现有服务的关联

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              现有信息服务 (只读数据源)                                 │
├─────────────────────┬─────────────────────┬─────────────────────────────────────────┤
│  trip-hotel-service  │  trip-attraction     │  trip-poi-service                      │
│  (酒店基础信息)       │  -service            │  (地点信息)                             │
│                      │  (景点基础信息)       │                                        │
└──────────┬──────────┴──────────┬──────────┴─────────────────────────────────────────┘
           │                     │
           │  resource_id        │  resource_id
           ▼                     ▼
┌──────────────────────────────────────────┐
│  trip-product-service                     │
│  ┌──────────────────────────────────────┐ │
│  │ SPU                                  │ │
│  │  resource_type: HOTEL_ROOM           │ │
│  │  resource_id:   hotel_room_123  ─────┼─┼──▶ Hotel Service
│  │                                      │ │
│  │  resource_type: ATTRACTION           │ │
│  │  resource_id:   attraction_456  ─────┼─┼──▶ Attraction Service
│  └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

SPU 通过 `resource_type` + `resource_id` 关联到酒店或景点，前端展示时可聚合两侧数据。

---

## 3. Proto 接口设计

### 3.1 trip-product-service

**Proto 文件**: `contracts/protobuf/tripsphere/product/v1/product.proto`

#### 核心模型

| 模型 | 说明 |
|------|------|
| `StandardProductUnit` (SPU) | 商品单元，关联一个业务资源 (酒店房型/景点)，包含名称、描述、图片、灵活属性 |
| `StockKeepingUnit` (SKU) | 销售单元，挂在 SPU 下，代表一个具体可售规格，含 `base_price` 参考价 |
| `ResourceType` | `HOTEL_ROOM` / `ATTRACTION` |
| `SpuStatus` | `DRAFT` → `ON_SHELF` → `OFF_SHELF` / `DELETED` |
| `SkuStatus` | `ACTIVE` / `INACTIVE` / `DELETED` |

#### gRPC 接口

| RPC | 说明 |
|-----|------|
| `CreateSpu` | 创建 SPU（可同时带 SKU） |
| `BatchCreateSpus` | 批量创建 |
| `GetSpuById` | 查询 SPU 详情（含嵌套 SKU） |
| `BatchGetSpus` | 批量查询 SPU |
| `ListSpusByResource` | 按 resource_type + resource_id 查询 SPU 列表 |
| `UpdateSpu` | 部分更新 (FieldMask) |
| `GetSkuById` | 按 ID 查询单个 SKU（供 Order/Inventory 服务调用） |
| `BatchGetSkus` | 批量查询 SKU |

#### 数据示例

```
SPU:
  id: "spu_001"
  name: "故宫博物院门票"
  resource_type: ATTRACTION
  resource_id: "attraction_gugong"
  status: ON_SHELF
  skus:
    - id: "sku_001", name: "成人票", base_price: ¥60, status: ACTIVE
    - id: "sku_002", name: "学生票", base_price: ¥20, status: ACTIVE

SPU:
  id: "spu_002"
  name: "北京饭店 - 标准双床房"
  resource_type: HOTEL_ROOM
  resource_id: "hotel_bj_001"
  status: ON_SHELF
  skus:
    - id: "sku_003", name: "含早", base_price: ¥580, status: ACTIVE
    - id: "sku_004", name: "不含早", base_price: ¥520, status: ACTIVE
```

### 3.2 trip-inventory-service

**Proto 文件**: `contracts/protobuf/tripsphere/inventory/v1/inventory.proto`

#### 核心模型

| 模型 | 说明 |
|------|------|
| `DailyInventory` | 一条 (sku_id, date) 记录，包含 total / available / locked / sold 四个数量 + 当日价格 |
| `InventoryLock` | 一次锁定操作，包含多个 LockItem，关联 order_id，有超时时间 |
| `LockItem` | 锁定的最小粒度：(sku_id, date, quantity) |
| `LockStatus` | `LOCKED` → `CONFIRMED` / `RELEASED` / `EXPIRED` |

#### gRPC 接口

| RPC | 类型 | 说明 |
|-----|------|------|
| `SetDailyInventory` | 管理 | 设置某 SKU 某天的总库存和价格 (Upsert) |
| `BatchSetDailyInventory` | 管理 | 批量设置 |
| `GetDailyInventory` | 查询 | 查询单个 (sku, date) 的库存与价格 |
| `QueryInventoryCalendar` | 查询 | 查询日期区间的库存日历 |
| `CheckAvailability` | 查询 | 快速检查 (sku, date, qty) 是否可售 |
| `LockInventory` | 事务 | 原子锁定一组 (sku, date, qty)，全部成功或全部失败 |
| `ConfirmLock` | 事务 | 支付成功后确认锁定，locked → sold |
| `ReleaseLock` | 事务 | 取消或超时后释放锁定，locked → available |

#### 库存数量关系

```
total_quantity = available_quantity + locked_quantity + sold_quantity
```

- `SetDailyInventory` 设置 `total_quantity`，系统自动计算 `available = total - locked - sold`
- `LockInventory`: `available -= qty`, `locked += qty`
- `ConfirmLock`: `locked -= qty`, `sold += qty`
- `ReleaseLock`: `locked -= qty`, `available += qty`

### 3.3 trip-order-service

**Proto 文件**: `contracts/protobuf/tripsphere/order/v1/order.proto`

#### 核心模型

| 模型 | 说明 |
|------|------|
| `Order` | 订单主体，包含订单项列表、联系人、总金额、来源、时间线 |
| `OrderItem` | 订单项，快照商品信息 + 日期 + 数量 + 价格 + inventory_lock_id |
| `ContactInfo` | 联系人 (name / phone / email) |
| `OrderSource` | 订单来源 (channel / agent_id / session_id)，区分人工和 Agent |
| `OrderStatus` | `PENDING_PAYMENT` → `PAID` → `COMPLETED` / `CANCELLED` |

#### gRPC 接口

| RPC | 说明 |
|-----|------|
| `CreateOrder` | 创建订单，内部编排 Saga（校验商品 → 锁库存 → 生成订单） |
| `GetOrder` | 查询订单详情 |
| `ListUserOrders` | 查询用户订单列表（支持按状态过滤、分页） |
| `CancelOrder` | 取消订单（释放库存锁定） |
| `ConfirmPayment` | 模拟支付确认（确认库存锁定） |

---

## 4. 核心流程

### 4.1 下单流程 (CreateOrder Saga)

Order Service 作为 Saga 编排者，依次调用 Product 和 Inventory 服务：

```
         Client / Agent
               │
               │  CreateOrder(user_id, items, contact, source)
               ▼
     ┌─────────────────────┐
     │   Order Service      │
     │   (Saga Orchestrator)│
     └──────────┬──────────┘
                │
   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ Saga 开始
                │
    Step 1      │  BatchGetSkus(sku_ids)
                ├─────────────────────────────▶ Product Service
                │  ◀── 返回 SKU 详情 (校验存在 & ACTIVE，拿 name/base_price 做快照)
                │
    Step 2      │  LockInventory(items, order_id)
                ├─────────────────────────────▶ Inventory Service
                │  ◀── 返回 InventoryLock (lock_id, 每日实际价格)
                │
    Step 3      │  本地创建 Order
                │  - 填入商品快照 (product_name, sku_name)
                │  - 填入实际价格 (从 Inventory 获取的日历价)
                │  - 填入 inventory_lock_id
                │  - status = PENDING_PAYMENT
                │  - expire_at = now + 15min
                │
   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ Saga 结束
                │
                ▼
          返回 Order 给调用方
```

**补偿逻辑**: 如果 Step 3 (本地建单) 失败，则调用 `ReleaseLock` 释放已锁定的库存。

### 4.2 支付确认流程

```
   Client / Agent
        │
        │  ConfirmPayment(order_id, payment_method)
        ▼
   Order Service
        │
        │ ① 校验 Order status == PENDING_PAYMENT && 未过期
        │
        │ ② ConfirmLock(lock_id)
        ├────────────────────────────▶ Inventory Service
        │    locked → sold (不可逆)
        │
        │ ③ UPDATE Order
        │    status = PAID
        │    paid_at = now
        │
        ▼
   返回更新后的 Order
```

### 4.3 取消订单流程

```
   Client / Agent
        │
        │  CancelOrder(order_id, reason)
        ▼
   Order Service
        │
        │ ① 校验 Order status == PENDING_PAYMENT
        │    (已支付的订单取消需走退款，MVP 暂不支持)
        │
        │ ② ReleaseLock(lock_id, reason)
        ├────────────────────────────▶ Inventory Service
        │    locked → available (库存回滚)
        │
        │ ③ UPDATE Order
        │    status = CANCELLED
        │    cancel_reason = reason
        │    cancelled_at = now
        │
        ▼
   返回更新后的 Order
```

### 4.4 订单超时自动关闭

```
   ┌──────────────────────────────────────────────────────┐
   │  Redis 延迟队列 / 定时任务                             │
   │                                                       │
   │  下单时:                                               │
   │    ZADD order:expire {score=expire_timestamp} order_id │
   │                                                       │
   │  定时轮询 (每 30s):                                    │
   │    ZRANGEBYSCORE order:expire 0 {now}                  │
   │    对每个过期 order_id:                                 │
   │      → CancelOrder(order_id, "支付超时自动取消")         │
   └──────────────────────────────────────────────────────┘
```

### 4.5 订单状态机

```
                  创建订单
                     │
                     ▼
           ┌─────────────────┐
           │ PENDING_PAYMENT  │
           └────────┬────────┘
                    │
          ┌─────────┼─────────┐
          │                   │
     支付成功            超时/用户取消
          │                   │
          ▼                   ▼
   ┌──────────┐       ┌────────────┐
   │   PAID   │       │ CANCELLED  │
   └────┬─────┘       └────────────┘
        │
   核销/完成
        │
        ▼
  ┌───────────┐
  │ COMPLETED │
  └───────────┘
```

---

## 5. 数据库设计

### 5.1 trip-product-service (MongoDB)

```
Collection: spus
{
  _id: ObjectId,
  name: String,
  description: String,
  resource_type: String,          // "HOTEL_ROOM" | "ATTRACTION"
  resource_id: String,            // 关联的酒店/景点 ID
  images: [String],
  status: String,                 // "DRAFT" | "ON_SHELF" | "OFF_SHELF" | "DELETED"
  attributes: Object,             // 灵活属性 (Struct)
  skus: [                         // 嵌入式 SKU
    {
      _id: String,
      name: String,
      description: String,
      status: String,
      attributes: Object,
      base_price: {
        currency: String,
        units: Long,
        nanos: Int
      }
    }
  ]
}

索引:
  - { resource_type: 1, resource_id: 1 }
  - { status: 1 }
  - { "skus._id": 1 }              // 支持 GetSkuById 查询
```

### 5.2 trip-inventory-service (MySQL + Redis)

#### MySQL 表

```sql
-- 日历库存表
CREATE TABLE daily_inventory (
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
);

-- 库存锁定表
CREATE TABLE inventory_lock (
    lock_id     VARCHAR(64)   PRIMARY KEY,
    order_id    VARCHAR(64)   NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'LOCKED',   -- LOCKED / CONFIRMED / RELEASED / EXPIRED
    created_at  BIGINT        NOT NULL,
    expire_at   BIGINT        NOT NULL,
    INDEX idx_order_id (order_id),
    INDEX idx_status_expire (status, expire_at)
);

-- 锁定明细表
CREATE TABLE inventory_lock_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_id     VARCHAR(64)   NOT NULL,
    sku_id      VARCHAR(64)   NOT NULL,
    inv_date    DATE          NOT NULL,
    quantity    INT           NOT NULL,
    INDEX idx_lock_id (lock_id)
);
```

#### Redis 数据结构

```
# 库存热数据缓存 (Hash)
inv:{sku_id}:{date}
  total       → 10
  available   → 7
  locked      → 2
  sold        → 1
  price_units → 60
  price_nanos → 0
  price_ccy   → CNY

# 锁定超时 (Sorted Set)
inv:lock:expiry
  score = expire_timestamp
  member = lock_id
```

#### 库存锁定 Redis Lua 脚本

```lua
-- lock_inventory.lua
-- 对每个 (sku, date) 检查并原子扣减 available，增加 locked
-- 任一项库存不足则全部不执行

local n = tonumber(ARGV[1])  -- item 数量

-- Phase 1: 检查所有项的库存
for i = 1, n do
    local key = KEYS[i]
    local qty = tonumber(ARGV[i + 1])
    local available = tonumber(redis.call('HGET', key, 'available') or '0')
    if available < qty then
        return {0, key, available}  -- 失败: 返回库存不足的 key
    end
end

-- Phase 2: 全部通过，执行扣减
for i = 1, n do
    local key = KEYS[i]
    local qty = tonumber(ARGV[i + 1])
    redis.call('HINCRBY', key, 'available', -qty)
    redis.call('HINCRBY', key, 'locked', qty)
end

return {1}  -- 成功
```

### 5.3 trip-order-service (MySQL + Redis)

#### MySQL 表

```sql
-- 订单主表
CREATE TABLE orders (
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
);

-- 订单项表
CREATE TABLE order_items (
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
);
```

#### Redis 数据结构

```
# 订单超时关闭 (Sorted Set)
order:expire
  score = expire_timestamp
  member = order_id
```

---

## 6. 项目结构

三个服务统一采用如下分层结构，与现有 trip-poi-service / trip-itinerary-service 保持一致：

```
trip-{service}-service/
├── pom.xml
├── src/main/java/org/tripsphere/{service}/
│   ├── {Service}Application.java
│   │
│   ├── api/grpc/                   # gRPC 接入层
│   │   ├── {Service}GrpcService.java
│   │   ├── MetadataGrpcService.java
│   │   ├── interceptor/
│   │   │   └── LoggingInterceptor.java
│   │   └── advice/
│   │       └── GrpcExceptionAdvice.java
│   │
│   ├── service/                    # 业务逻辑层
│   │   ├── {Service}Service.java          (接口)
│   │   └── impl/
│   │       └── {Service}ServiceImpl.java  (实现)
│   │
│   ├── repository/                 # 数据访问层
│   │   ├── {Entity}Repository.java
│   │   └── impl/                   (自定义查询实现)
│   │
│   ├── model/                      # 领域模型 / 持久化实体
│   │   └── {Entity}Doc.java / {Entity}Entity.java
│   │
│   ├── mapper/                     # Protobuf ↔ 领域模型 映射 (MapStruct)
│   │   └── {Entity}Mapper.java
│   │
│   ├── config/                     # 配置类
│   │
│   ├── security/                   # 认证鉴权
│   │   ├── AuthInterceptor.java
│   │   └── GrpcAuthContext.java
│   │
│   └── exception/                  # 业务异常
│       ├── BusinessException.java
│       ├── NotFoundException.java
│       └── ...
│
└── src/main/resources/
    ├── application.yaml
    └── application-dev.yaml
```

### 各服务特殊模块

| 服务 | 额外模块 | 说明 |
|------|----------|------|
| trip-inventory-service | `infra/redis/` | Redis 操作封装，Lua 脚本加载 |
| trip-inventory-service | `scheduler/` | 锁定超时释放定时任务 |
| trip-order-service | `saga/` | Saga 编排器，管理 CreateOrder 的跨服务事务 |
| trip-order-service | `grpc/client/` | 调用 Product / Inventory 服务的 gRPC Stub 封装 |
| trip-order-service | `scheduler/` | 订单超时关闭定时任务 |

---

## 7. 关键实现要点

### 7.1 库存一致性 (Inventory Service)

| 策略 | 实现 |
|------|------|
| Redis 原子操作 | 锁定/释放通过 Lua 脚本保证原子性，避免并发超卖 |
| Redis → MySQL 同步 | 写操作先更新 Redis，再异步或同步落 MySQL；读操作优先 Redis |
| 锁定超时清理 | 定时任务扫描 `inv:lock:expiry`，过期锁自动释放并回写 MySQL |
| 缓存预热 | 服务启动时 / 管理员设置库存时加载到 Redis |

### 7.2 Saga 补偿 (Order Service)

| 场景 | 补偿动作 |
|------|----------|
| Product 校验失败 (SKU 不存在/下架) | 直接返回错误，无需补偿 |
| Inventory 锁定失败 (库存不足) | 直接返回错误，无需补偿 |
| 本地建单失败 (DB 异常) | 调用 `ReleaseLock` 释放已锁定库存 |
| 支付确认时 `ConfirmLock` 失败 | 重试；超过重试次数则标记订单为异常，人工介入 |

### 7.3 订单号生成

```
格式: TS + yyyyMMdd + 6位序列号
示例: TS20260208000001

实现: Redis INCR order:seq:{yyyyMMdd}，每天自动从 1 开始
```

### 7.4 幂等性

| 接口 | 幂等策略 |
|------|----------|
| `CreateOrder` | 基于 `(user_id, sku_id, date, 时间窗口)` 防重复提交 |
| `ConfirmPayment` | 基于 `order_id` 状态机校验，PAID 状态不可重复确认 |
| `CancelOrder` | 基于 `order_id` 状态机校验，已取消/已支付不可重复取消 |
| `LockInventory` | 基于 `order_id` 去重，同一订单不重复锁定 |
| `ConfirmLock` / `ReleaseLock` | 基于 `lock_id` + `LockStatus` 状态机校验 |

---

## 8. Agent 集成 (MCP Tools)

三个服务的 gRPC 接口封装为 MCP Tools 后，供 Agent 通过 A2A 协议调用：

```
Agent (journey-assistant / itinerary-planner)
  │
  │  MCP Tools (gRPC → MCP 封装)
  │
  ├── search_products(resource_type, resource_id)    → ProductService.ListSpusByResource
  ├── get_product_detail(spu_id)                     → ProductService.GetSpuById
  ├── check_availability(sku_id, date, quantity)     → InventoryService.CheckAvailability
  ├── get_price_calendar(sku_id, start, end)         → InventoryService.QueryInventoryCalendar
  ├── create_order(user_id, items, contact, source)  → OrderService.CreateOrder
  ├── get_order(order_id)                            → OrderService.GetOrder
  ├── list_user_orders(user_id, status)              → OrderService.ListUserOrders
  ├── cancel_order(order_id, reason)                 → OrderService.CancelOrder
  └── confirm_payment(order_id, payment_method)      → OrderService.ConfirmPayment
```

### Agent 下单示例对话

```
用户: 帮我订明天去故宫的门票，2张成人票

Agent 内部调用链:
  1. search_products(ATTRACTION, "故宫") → 找到 SPU，获取 SKU 列表
  2. check_availability("sku_成人票", 明天, 2) → 确认有票，获取价格
  3. 向用户确认: "故宫成人票 x2，明天，共 ¥120，确认下单吗？"
  4. 用户确认后: create_order(user_id, [{sku_id, date, qty=2}], contact, {channel:"agent"})
  5. 返回: "订单创建成功！订单号 TS20260209000001，请在 15 分钟内支付"
```

`OrderSource.channel = "agent"` + `agent_id` + `session_id` 使得 Agent
创建的订单可追溯到具体对话会话。

---

## 9. 实施计划

| 阶段 | 内容 | 前置依赖 |
|------|------|----------|
| Phase 1 | Proto 定义 (已完成) | — |
| Phase 2 | trip-product-service 实现 | Proto 代码生成 |
| Phase 3 | trip-inventory-service 实现 | Phase 2 (需要 SKU 校验) |
| Phase 4 | trip-order-service 实现 (含 Saga) | Phase 2 + Phase 3 |
| Phase 5 | MCP Tools 封装 + Agent 集成 | Phase 4 |
| Phase 6 | 前端订单页面集成 | Phase 4 |

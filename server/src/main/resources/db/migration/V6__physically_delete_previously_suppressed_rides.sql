-- RFC-015：V4 的“隐藏”记录已被用户明确确认为删除指令。
-- 先把目标 ID 复制到临时表，后续即使先删审计行也能按同一集合清理所有关联数据。
CREATE TEMPORARY TABLE v6_deleted_ride_ids (
  ride_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=MEMORY;

INSERT INTO v6_deleted_ride_ids (ride_id)
SELECT ride_id FROM admin_ride_suppression;

-- chat_message 的 Outbox 以 message id 为 aggregate_id，必须在消息删除前清理。
DELETE oe
FROM outbox_event oe
JOIN chat_message cm ON oe.aggregate_type = 'chat_message' AND oe.aggregate_id = cm.id
JOIN chat_session cs ON cm.session_id = cs.id
JOIN v6_deleted_ride_ids ids ON cs.ride_id = ids.ride_id;

-- 消息外键依赖会话，严格先消息后会话。
DELETE cm
FROM chat_message cm
JOIN chat_session cs ON cm.session_id = cs.id
JOIN v6_deleted_ride_ids ids ON cs.ride_id = ids.ride_id;

DELETE cs
FROM chat_session cs
JOIN v6_deleted_ride_ids ids ON cs.ride_id = ids.ride_id;

-- 订单事件及订单幂等记录不能在订单已不存在后被异步/重放逻辑继续使用。
DELETE oe
FROM outbox_event oe
JOIN v6_deleted_ride_ids ids ON oe.aggregate_type = 'ride' AND oe.aggregate_id = ids.ride_id;

DELETE ir
FROM idempotency_record ir
JOIN v6_deleted_ride_ids ids ON ir.resource_id = ids.ride_id
WHERE ir.operation LIKE 'ride.%';

DELETE rm
FROM ride_member rm
JOIN v6_deleted_ride_ids ids ON rm.ride_id = ids.ride_id;

-- 先移除 V4 审计外键，再删除 Ride 主记录。
DELETE ars
FROM admin_ride_suppression ars
JOIN v6_deleted_ride_ids ids ON ars.ride_id = ids.ride_id;

DELETE r
FROM ride r
JOIN v6_deleted_ride_ids ids ON r.id = ids.ride_id;

DROP TEMPORARY TABLE v6_deleted_ride_ids;

-- V6 起不再使用隐藏语义；移除空表，杜绝后续代码误把物理删除改回软删除。
DROP TABLE admin_ride_suppression;

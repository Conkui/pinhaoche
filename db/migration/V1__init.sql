-- V1 核心表。所有时间以 UTC 保存；业务显示时由客户端转换时区。
CREATE TABLE app_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, openid VARCHAR(128) NOT NULL UNIQUE,
  nickname VARCHAR(100) NOT NULL, avatar VARCHAR(512), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE place (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_place_name (name), KEY idx_place_enabled_sort (enabled, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ride (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, owner_id BIGINT NOT NULL,
  origin_type VARCHAR(10) NOT NULL, origin_place_id BIGINT NULL, origin_text VARCHAR(100) NOT NULL,
  destination_type VARCHAR(10) NOT NULL, destination_place_id BIGINT NULL, destination_text VARCHAR(100) NOT NULL,
  depart_time DATETIME(3) NOT NULL, member_count INT NOT NULL, capacity INT NOT NULL, status VARCHAR(20) NOT NULL,
  plate_number VARCHAR(32), provider VARCHAR(32), remaining_distance DECIMAL(8,2), distance_updated_at DATETIME(3),
  waiting_minutes INT NULL, version BIGINT NOT NULL DEFAULT 0, remark VARCHAR(300), created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_ride_owner FOREIGN KEY (owner_id) REFERENCES app_user(id),
  CONSTRAINT ck_ride_capacity CHECK (capacity >= member_count AND member_count > 0),
  KEY idx_ride_status_depart (status, depart_time), KEY idx_ride_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ride_member (
  ride_id BIGINT NOT NULL, user_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL, created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (ride_id, user_id), CONSTRAINT fk_member_ride FOREIGN KEY (ride_id) REFERENCES ride(id),
  CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, ride_id BIGINT NOT NULL, user_low_id BIGINT NOT NULL, user_high_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  CONSTRAINT ck_session_order CHECK (user_low_id < user_high_id), UNIQUE KEY uk_session_ride_pair (ride_id, user_low_id, user_high_id),
  KEY idx_session_ride (ride_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, sender_id BIGINT NOT NULL, client_msg_id CHAR(36) NOT NULL,
  type VARCHAR(20) NOT NULL, content VARCHAR(2000) NOT NULL, created_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES chat_session(id),
  UNIQUE KEY uk_message_sender_client (sender_id, client_msg_id), KEY idx_message_session_id (session_id, id), KEY idx_message_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, aggregate_type VARCHAR(50) NOT NULL, aggregate_id BIGINT NOT NULL, event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', created_at DATETIME(3) NOT NULL, published_at DATETIME(3),
  KEY idx_outbox_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Redis SETNX 只降低重复请求压力；此唯一键才是重启/过期后的最终幂等保障。
CREATE TABLE idempotency_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, actor_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
  operation VARCHAR(80) NOT NULL, request_hash CHAR(64) NOT NULL, resource_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_idempotency_actor_key (actor_id, idempotency_key), KEY idx_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

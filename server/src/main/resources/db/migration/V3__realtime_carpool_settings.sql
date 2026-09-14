-- V3: 实时拼功能开关与停用公告。V1 冻结后新增独立配置表，不触碰 Ride 聚合或状态机。
CREATE TABLE realtime_carpool_setting (
  id TINYINT PRIMARY KEY,
  enabled BOOLEAN NOT NULL,
  announcement VARCHAR(500) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT chk_realtime_carpool_setting_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 上线默认保持开启；管理员仅在需要临时停用实时拼时关闭。
INSERT INTO realtime_carpool_setting (id, enabled, announcement, updated_at)
VALUES (1, TRUE, '实时拼功能暂未开启，请留意后续公告。', UTC_TIMESTAMP(3));

-- V5: “实时拼”（已打上车）和“找拼友”（预打车）是两项独立运营功能。
-- 旧 V3 开关实际控制找拼友，因此保留其状态和公告到新表；实时拼上线默认开启。
CREATE TABLE find_friends_setting (
  id TINYINT PRIMARY KEY,
  enabled BOOLEAN NOT NULL,
  announcement VARCHAR(500) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT chk_find_friends_setting_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO find_friends_setting (id, enabled, announcement, updated_at)
SELECT id, enabled, announcement, updated_at FROM realtime_carpool_setting WHERE id = 1;

-- 新的实时拼开关只拦截“我已打上车”的发布/加入路径，历史公告不能误影响它。
UPDATE realtime_carpool_setting
SET enabled = TRUE,
    announcement = '实时拼功能暂未开启，请留意后续公告。',
    updated_at = UTC_TIMESTAMP(3)
WHERE id = 1;

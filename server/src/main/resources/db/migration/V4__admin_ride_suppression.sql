-- V4: 管理端删除拼车单采用独立的软删除审计，不修改冻结的 ride 状态机或 ride 表。
-- 读取路径接入 admin_ride_suppression 前，记录仅表示已收到删除指令，不能假称用户端已隐藏。
CREATE TABLE admin_ride_suppression (
  ride_id BIGINT NOT NULL,
  deleted_at DATETIME(3) NOT NULL,
  PRIMARY KEY (ride_id),
  CONSTRAINT fk_admin_ride_suppression_ride FOREIGN KEY (ride_id) REFERENCES ride(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

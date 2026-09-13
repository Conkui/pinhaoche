-- V2: 用户意见反馈（RFC-006）。V1 冻结表结构不变；新增能力走新迁移。
CREATE TABLE feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL,
  content VARCHAR(500) NOT NULL, created_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  KEY idx_feedback_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- V7: chat_session 必须引用真实 Ride。管理员持有 Ride 的排他锁时，
-- 并发建会话会受该外键约束阻塞；订单提交删除后，建会话事务将因父记录不存在而回滚，避免孤儿会话。
ALTER TABLE chat_session
  ADD CONSTRAINT fk_chat_session_ride FOREIGN KEY (ride_id) REFERENCES ride(id);

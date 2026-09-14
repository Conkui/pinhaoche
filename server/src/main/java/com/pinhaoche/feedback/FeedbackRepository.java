package com.pinhaoche.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    /** RFC-009 管理端：反馈分页列表（调用方按 createdAt 倒序传入 Pageable，这里用派生方法固化排序，避免各处排序不一致）。 */
    Page<Feedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** RFC-009 统计：某时间点（含）之后创建的反馈数，用于"今日新增"。 */
    long countByCreatedAtAfter(OffsetDateTime time);
}

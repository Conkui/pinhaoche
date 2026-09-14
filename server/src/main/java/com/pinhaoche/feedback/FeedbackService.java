package com.pinhaoche.feedback;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Feedback 应用服务（RFC-006/009）：反馈本身 V1 只存不审，这里只补管理端只读能力
 * （分页列表 + 统计计数），供 AdminFeedbackController 与 admin 模块 StatsController 调用。
 */
@Service
public class FeedbackService {

    /** 分页 size 上限：后台一次拉太多没有意义，超过按 100 截断（与管理端其他列表口径一致）。 */
    static final int MAX_PAGE_SIZE = 100;

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    // ---------- DTO ----------

    public record FeedbackItem(long id, long userId, String content, OffsetDateTime createdAt) {
    }

    /** 管理端统一分页响应壳：total 全量总数，page/size 回显规范化后的值。 */
    public record FeedbackPage(long total, int page, int size, List<FeedbackItem> items) {
    }

    // ---------- 查询 ----------

    /**
     * 反馈分页列表，createdAt 倒序。
     * page 从 0 起；size<=0 视为 20（默认值），size>100 按 100 截断——
     * 边界值直接规范化而不是报错，管理端误传参数时仍能返回合理结果。
     */
    @Transactional(readOnly = true)
    public FeedbackPage page(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(p, s);
        var result = feedbackRepository.findAllByOrderByCreatedAtDesc(pageable);
        List<FeedbackItem> items = result.getContent().stream()
                .map(f -> new FeedbackItem(f.getId(), f.getUserId(), f.getContent(), f.getCreatedAt()))
                .toList();
        return new FeedbackPage(result.getTotalElements(), p, s, items);
    }

    // ---------- 统计（RFC-009，admin 模块经此调用，不碰本模块 repository） ----------

    @Transactional(readOnly = true)
    public long countAll() {
        return feedbackRepository.count();
    }

    /** 时间边界统一用 UTC：DB 存 UTC，用本地时区统计会漏算/多算跨时区小时。 */
    @Transactional(readOnly = true)
    public long countCreatedAfter(OffsetDateTime time) {
        return feedbackRepository.countByCreatedAtAfter(time);
    }
}

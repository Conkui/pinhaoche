package com.pinhaoche.feedback;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端反馈查看（RFC-009）：/api/v1/admin/** 已被 WebConfig 的 X-Admin-Key 拦截器保护，
 * 这里不做额外鉴权；只读接口，放 feedback 模块内（先例：place/AdminPlaceController）。
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    public AdminFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** page 从 0 起，size 上限 100（超限在 service 内规范化，不报错）。 */
    @GetMapping
    public FeedbackService.FeedbackPage list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return feedbackService.page(page, size);
    }
}

package com.pinhaoche.feedback;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意见反馈（RFC-006）：入口在"我的"页，不与主功能抢位置（用户指令）。
 * 登录态必填（token 解析身份，防匿名灌水）；V1 只存不审。
 */
@RestController
@RequestMapping("/api/v1")
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;

    public FeedbackController(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    public record FeedbackRequest(@NotBlank @Size(max = 500) String content) {
    }

    @PostMapping("/feedback")
    public org.springframework.http.ResponseEntity<Void> submit(@RequestBody FeedbackRequest request,
                                                                HttpServletRequest http) {
        Object v = http.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (v == null) {
            throw ApiException.unauthorized("未登录");
        }
        feedbackRepository.save(new Feedback((Long) v, request.content().trim(),
                java.time.OffsetDateTime.now()));
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}

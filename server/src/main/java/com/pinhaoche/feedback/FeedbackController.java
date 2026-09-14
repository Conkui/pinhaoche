package com.pinhaoche.feedback;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.auth.AuthInterceptor;
import com.pinhaoche.common.util.TextSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    public org.springframework.http.ResponseEntity<Void> submit(@Valid @RequestBody FeedbackRequest request,
                                                                HttpServletRequest http) {
        Object v = http.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (v == null) {
            throw ApiException.unauthorized("未登录");
        }
        // RFC-010 输入净化：反馈内容入库前剥离控制字符（clean 已含 trim），防止灌水脚本用
        // 控制字符污染管理端展示与日志；与聊天消息共用同一净化入口，保证库内文本"干净"。
        // 顺带补上 @Valid：原 @NotBlank/@Size 因缺注解从未生效（null content 会 NPE），
        // 生效后由 GlobalExceptionHandler 统一返回 400 VALIDATION_ERROR。
        String content = TextSanitizer.clean(request.content());
        feedbackRepository.save(new Feedback((Long) v, content,
                java.time.OffsetDateTime.now()));
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}

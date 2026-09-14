package com.pinhaoche.admin;

import com.pinhaoche.chat.ChatService;
import com.pinhaoche.feedback.FeedbackService;
import com.pinhaoche.ride.RideService;
import com.pinhaoche.ride.RideStatus;
import com.pinhaoche.user.UserStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端流量统计总览（RFC-009）。
 * 模块边界（硬性）：只调各模块 application service（UserStatsService/RideService/ChatService/FeedbackService），
 * 禁止注入其他模块 repository；鉴权由 WebConfig 的 X-Admin-Key 拦截器统一处理。
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private final UserStatsService userStats;
    private final RideService rideService;
    private final ChatService chatService;
    private final FeedbackService feedbackService;

    public AdminStatsController(UserStatsService userStats, RideService rideService,
                                ChatService chatService, FeedbackService feedbackService) {
        this.userStats = userStats;
        this.rideService = rideService;
        this.chatService = chatService;
        this.feedbackService = feedbackService;
    }

    // ---------- 响应体 ----------

    public record DailyStat(String date, long rides, long messages) {
    }

    public record Overview(long users, long ridesTotal, long ridesActive, long ridesBooked, long ridesExpired,
                           long sessions, long messages, long feedbacks,
                           long todayNewUsers, long todayNewRides, long todayNewMessages, long todayNewFeedbacks,
                           List<DailyStat> daily) {
    }

    /** 活跃单口径：仍在流转、用户可见/可操作的状态（EXPIRED/CANCELLED/CLOSED/ARRIVED/DRAFT 不算）。 */
    private static final List<RideStatus> ACTIVE_STATUSES =
            List.of(RideStatus.PUBLISHED, RideStatus.MATCHING, RideStatus.BOOKED, RideStatus.IN_PROGRESS);

    /** daily 覆盖最近 7 天（含今天）。 */
    private static final int DAILY_DAYS = 7;

    @GetMapping("/overview")
    public Overview overview() {
        // 全部时间边界用 UTC：实体 createdAt 为 OffsetDateTime 且 DB 存 UTC，
        // 用服务器本地时区统计"今日"会在跨时区部署时漏算/多算
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // LocalDate.atStartOfDay(zone) 返回 ZonedDateTime，实体字段是 OffsetDateTime，
        // 按契约统一 .toOffsetDateTime() 转换，保证传给各 service 的边界类型一致
        OffsetDateTime todayStart = today.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        List<DailyStat> daily = new ArrayList<>(DAILY_DAYS);
        // 升序输出：从 6 天前循环到今天
        for (int i = DAILY_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            OffsetDateTime from = day.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            OffsetDateTime to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            daily.add(new DailyStat(day.toString(),
                    rideService.countCreatedBetween(from, to),
                    chatService.countMessagesCreatedBetween(from, to)));
        }

        return new Overview(
                userStats.countAll(),
                rideService.countByStatusIn(List.of(RideStatus.values())),
                rideService.countByStatusIn(ACTIVE_STATUSES),
                rideService.countByStatusIn(List.of(RideStatus.BOOKED)),
                rideService.countByStatusIn(List.of(RideStatus.EXPIRED)),
                chatService.countSessions(),
                chatService.countMessages(),
                feedbackService.countAll(),
                userStats.countCreatedAfter(todayStart),
                rideService.countCreatedAfter(todayStart),
                chatService.countMessagesCreatedAfter(todayStart),
                feedbackService.countCreatedAfter(todayStart),
                daily);
    }
}

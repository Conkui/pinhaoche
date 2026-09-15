package com.pinhaoche.ride;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RideRepository extends JpaRepository<Ride, Long> {

    /** 管理员物理删除前锁住订单行，阻止并发加入/状态迁移在清理 Outbox 后写入新事件。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Ride r WHERE r.id = :id")
    Optional<Ride> findByIdForAdminDeletion(@Param("id") long id);

    List<Ride> findByStatusOrderByUpdatedAtDesc(RideStatus status);

    /** RFC-008：我的发布（含终态），按创建时间倒序。 */
    List<Ride> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /** 发布上限检查：产品将 PUBLISHED/MATCHING 定义为仍在“待同行”的预打车单。 */
    long countByOwnerIdAndStatusIn(long ownerId, List<RideStatus> statuses);

    /**
     * MySQL 命名锁按用户串行化跨 Ride 的“先计数、后写入”。单靠锁住某一条 Ride
     * 无法覆盖用户第一次并发发布/加入不同订单时“尚无可锁记录”的空集竞态。
     */
    @Query(value = "SELECT GET_LOCK(:lockName, 5)", nativeQuery = true)
    Integer acquireUserLimitLock(@Param("lockName") String lockName);

    /** 必须在获得锁的同一事务连接释放，避免连接复用时把后续请求无故阻塞。 */
    @Query(value = "SELECT RELEASE_LOCK(:lockName)", nativeQuery = true)
    Integer releaseUserLimitLock(@Param("lockName") String lockName);

    /**
     * 预打车列表（用户红线：时间效益）——只返回未过出发时间的单，
     * 已过出发时间的拼车单对任何人都不再展示，避免"今天还能看到昨天的单"。
     */
    List<Ride> findByStatusInAndDepartTimeAfterOrderByDepartTimeAsc(List<RideStatus> statuses, OffsetDateTime now);

    /** 过期任务用：已过出发时间但仍处于可展示状态的预打车单。 */
    List<Ride> findByStatusInAndDepartTimeBefore(List<RideStatus> statuses, OffsetDateTime now);

    /**
     * 加入成员（规范 §20）：禁止 SELECT→Java 判断→UPDATE，
     * 单条条件更新原子完成 人数+1 / PUBLISHED→MATCHING / 乐观锁；0 行受影响即失败（已满或并发竞争）。
     * clearAutomatically：批量 UPDATE 后清一级缓存，否则同事务内的后续读会拿到更新前的旧实体。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ride r
            SET r.memberCount = r.memberCount + 1,
                r.version = r.version + 1,
                r.status = CASE WHEN r.status = :fromPublished THEN :toMatching ELSE r.status END,
                r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id
              AND r.status IN :joinable
              AND r.memberCount < r.capacity
              AND r.version = :version
            """)
    int joinAtomically(@Param("id") long id,
                       @Param("version") long version,
                       @Param("joinable") List<RideStatus> joinable,
                       @Param("fromPublished") RideStatus fromPublished,
                       @Param("toMatching") RideStatus toMatching);

    /**
     * 显式状态迁移：乐观锁 + 当前状态校验，防并发双写；
     * 状态合法性由 RideStateMachine 在 service 层先校验，这里保证落库时状态未被并发改变。
     * clearAutomatically 同上：迁移后同事务读必须看到新状态。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ride r
            SET r.status = :target, r.version = r.version + 1, r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.version = :version AND r.status = :expected
            """)
    int transitAtomically(@Param("id") long id,
                          @Param("version") long version,
                          @Param("expected") RideStatus expected,
                          @Param("target") RideStatus target);

    // ---------- RFC-009 管理端只读统计（只加不改，均不带写语义） ----------

    /** 管理端按状态分页；排序由调用方 Pageable 携带。 */
    Page<Ride> findByStatus(RideStatus status, Pageable pageable);

    /** 活跃单（PUBLISHED/MATCHING/BOOKED/IN_PROGRESS）或任意状态集合的计数。 */
    long countByStatusIn(List<RideStatus> statuses);

    /** 某时间点（含）之后创建的单数，用于"今日新增"。 */
    long countByCreatedAtAfter(OffsetDateTime time);

    /**
     * 逐日统计用：[start, end) 半开区间计数。为什么用半开区间——
     * 按天分桶时上界取"次日零点"且不含，可避免毫秒级边界重复/遗漏，也无需处理 23:59:59.999 的精度问题。
     */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime start, OffsetDateTime end);
}

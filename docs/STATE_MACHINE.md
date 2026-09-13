# Ride 状态机

```text
DRAFT -> PUBLISHED -> MATCHING -> BOOKED -> IN_PROGRESS -> ARRIVED -> CLOSED
                   \-> CANCELLED                 \-> CANCELLED
任一未关闭有效状态可在业务规则允许时 -> EXPIRED
```

| 当前状态 | 允许目标状态 | 命令与约束 |
| --- | --- | --- |
| DRAFT | PUBLISHED, CANCELLED | 发布前字段完整；仅 owner |
| PUBLISHED | MATCHING, BOOKED, CANCELLED, EXPIRED | 有成员可转 MATCHING；成功导入并确认叫车信息才可转 BOOKED |
| MATCHING | BOOKED, CANCELLED, EXPIRED | 同上 |
| BOOKED | IN_PROGRESS, CANCELLED | owner 点击“不再等人”转 IN_PROGRESS |
| IN_PROGRESS | ARRIVED, CANCELLED | owner 更新行程 |
| ARRIVED | CLOSED | owner 完成行程 |
| CLOSED, CANCELLED, EXPIRED | 无 | 终态，不可恢复 |

加入成员仅允许 `PUBLISHED`、`MATCHING`、`BOOKED`，且不能加入自己的 Ride、不能重复加入、不能超过 capacity。状态判断必须在数据库版本条件更新中完成或在同一事务内锁定聚合；不得仅凭前端状态判断。


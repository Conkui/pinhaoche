package com.pinhaoche.ride;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** ride 表（V1__init.sql）。version 用于乐观并发控制，一切状态/人数变更必须带条件更新。 */
@Entity
@Table(name = "ride")
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ownerId;

    /** PRESET / CUSTOM */
    private String originType;
    private Long originPlaceId;
    /** 地点快照：即使 PRESET 也必须保存名称，防止预设地点改名/停用影响历史记录（规范 §5） */
    private String originText;

    private String destinationType;
    private Long destinationPlaceId;
    private String destinationText;

    private OffsetDateTime departTime;
    private Integer memberCount;
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    private RideStatus status;

    private String plateNumber;
    private String provider;
    private BigDecimal remainingDistance;
    private OffsetDateTime distanceUpdatedAt;
    private Integer waitingMinutes;
    private String remark;

    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOriginType() { return originType; }
    public void setOriginType(String originType) { this.originType = originType; }
    public Long getOriginPlaceId() { return originPlaceId; }
    public void setOriginPlaceId(Long originPlaceId) { this.originPlaceId = originPlaceId; }
    public String getOriginText() { return originText; }
    public void setOriginText(String originText) { this.originText = originText; }
    public String getDestinationType() { return destinationType; }
    public void setDestinationType(String destinationType) { this.destinationType = destinationType; }
    public Long getDestinationPlaceId() { return destinationPlaceId; }
    public void setDestinationPlaceId(Long destinationPlaceId) { this.destinationPlaceId = destinationPlaceId; }
    public String getDestinationText() { return destinationText; }
    public void setDestinationText(String destinationText) { this.destinationText = destinationText; }
    public OffsetDateTime getDepartTime() { return departTime; }
    public void setDepartTime(OffsetDateTime departTime) { this.departTime = departTime; }
    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public RideStatus getStatus() { return status; }
    public void setStatus(RideStatus status) { this.status = status; }
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public BigDecimal getRemainingDistance() { return remainingDistance; }
    public void setRemainingDistance(BigDecimal remainingDistance) { this.remainingDistance = remainingDistance; }
    public OffsetDateTime getDistanceUpdatedAt() { return distanceUpdatedAt; }
    public void setDistanceUpdatedAt(OffsetDateTime distanceUpdatedAt) { this.distanceUpdatedAt = distanceUpdatedAt; }
    public Integer getWaitingMinutes() { return waitingMinutes; }
    public void setWaitingMinutes(Integer waitingMinutes) { this.waitingMinutes = waitingMinutes; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}

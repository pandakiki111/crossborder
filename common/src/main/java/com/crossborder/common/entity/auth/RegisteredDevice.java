package com.crossborder.common.entity.auth;

import com.crossborder.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 작업자(WORKER) 로그인 허용 기기
 */
@Getter
@Entity
@Table(name = "registered_devices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegisteredDevice extends BaseEntity {

    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    /** 기기 식별 토큰 해시 (원문은 해당 PC 쿠키에만 존재) */
    @Column(name = "device_token", nullable = false, length = 255)
    private String deviceToken;

    @Column(length = 100)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status;

    /** 등록 코드를 발급한 관리자 id */
    @Column(name = "registered_by", nullable = false)
    private Long registeredBy;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    private RegisteredDevice(String deviceName, String deviceToken, String location, Long registeredBy) {
        this.deviceName = deviceName;
        this.deviceToken = deviceToken;
        this.location = location;
        this.registeredBy = registeredBy;
        this.status = DeviceStatus.ACTIVE;
    }

    public static RegisteredDevice create(String deviceName, String deviceTokenHash, String location,
                                          Long registeredBy) {
        return new RegisteredDevice(deviceName, deviceTokenHash, location, registeredBy);
    }

    public void changeInfo(String deviceName, String location) {
        this.deviceName = deviceName;
        this.location = location;
    }

    public void block() {
        this.status = DeviceStatus.BLOCKED;
    }

    public void unblock() {
        this.status = DeviceStatus.ACTIVE;
    }

    public void markUsed(LocalDateTime usedAt) {
        this.lastUsedAt = usedAt;
    }

    public boolean isActive() {
        return status == DeviceStatus.ACTIVE;
    }
}

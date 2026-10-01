package com.crossborder.common.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 감사 이력 (append-only)
 */
@Getter
@Entity
@Table(name = "login_histories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** WORKER 로그인 시 사용 기기, 관리자군은 null */
    @Column(name = "device_id", updatable = false)
    private Long deviceId;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "logged_in_at", nullable = false, updatable = false)
    private LocalDateTime loggedInAt;

    private LoginHistory(Long userId, Long deviceId, String ipAddress) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.ipAddress = ipAddress;
        this.loggedInAt = LocalDateTime.now();
    }

    public static LoginHistory ofAdminGroup(Long userId, String ipAddress) {
        return new LoginHistory(userId, null, ipAddress);
    }

    public static LoginHistory ofWorker(Long userId, Long deviceId, String ipAddress) {
        return new LoginHistory(userId, deviceId, ipAddress);
    }
}

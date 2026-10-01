package com.crossborder.common.entity.organization;

import com.crossborder.common.entity.ActiveStatus;
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
 * 입점 회사
 */
@Getter
@Entity
@Table(name = "companies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "business_no", length = 20)
    private String businessNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActiveStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private Company(String name, String businessNo) {
        this.name = name;
        this.businessNo = businessNo;
        this.status = ActiveStatus.ACTIVE;
    }

    public static Company create(String name, String businessNo) {
        return new Company(name, businessNo);
    }

    public void changeInfo(String name, String businessNo) {
        this.name = name;
        this.businessNo = businessNo;
    }

    public void activate() {
        if (isTerminated()) {
            throw new IllegalStateException("폐업/계약종료 처리된 회사는 활성화할 수 없습니다. id=" + getId());
        }
        this.status = ActiveStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = ActiveStatus.INACTIVE;
    }

    /**
     * 폐업/계약종료 처리. 소속 데이터(브랜드·사용자·상품 등)의 후속 처리는 서비스 책임
     */
    public void terminate() {
        if (isTerminated()) {
            throw new IllegalStateException("이미 폐업/계약종료 처리된 회사입니다. id=" + getId());
        }
        this.status = ActiveStatus.INACTIVE;
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isTerminated() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }
}

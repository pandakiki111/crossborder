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
 * 입점사 브랜드 (브랜드명 유니크는 회사 단위)
 */
@Getter
@Entity
@Table(name = "brands")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Brand extends BaseEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActiveStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private Brand(Long companyId, String name) {
        this.companyId = companyId;
        this.name = name;
        this.status = ActiveStatus.ACTIVE;
    }

    public static Brand create(Long companyId, String name) {
        return new Brand(companyId, name);
    }

    public void changeName(String name) {
        this.name = name;
    }

    public void activate() {
        if (isTerminated()) {
            throw new IllegalStateException("계약종료 처리된 브랜드는 활성화할 수 없습니다. id=" + getId());
        }
        this.status = ActiveStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = ActiveStatus.INACTIVE;
    }

    /**
     * 계약종료 처리. 소속 데이터(사용자·상품·판매상품 등)의 후속 처리는 서비스 책임
     */
    public void terminate() {
        if (isTerminated()) {
            throw new IllegalStateException("이미 계약종료 처리된 브랜드입니다. id=" + getId());
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

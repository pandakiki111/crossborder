package com.crossborder.common.entity.product;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매채널 마스터 (RAKUTEN / QOO10 / AMAZON_JP ...)
 */
@Getter
@Entity
@Table(name = "sales_channels")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesChannel extends BaseEntity {

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActiveStatus status;

    private SalesChannel(String code, String name) {
        this.code = code;
        this.name = name;
        this.status = ActiveStatus.ACTIVE;
    }

    public static SalesChannel create(String code, String name) {
        return new SalesChannel(code, name);
    }

    public void changeName(String name) {
        this.name = name;
    }

    public void activate() {
        this.status = ActiveStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = ActiveStatus.INACTIVE;
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }
}

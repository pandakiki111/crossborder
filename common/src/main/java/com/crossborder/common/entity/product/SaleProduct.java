package com.crossborder.common.entity.product;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매상품 (마켓 노출 단위). 단품도 구성 1행짜리 판매상품으로 등록한다.
 * <p>
 * 주문 이력이 있는 판매상품의 구성(SaleProductItem) 변경은 금지 — 서비스 레이어 검증
 */
@Getter
@Entity
@Table(name = "sale_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleProduct extends BaseAuditEntity {

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(nullable = false, length = 300)
    private String name;

    /** 내부 식별자라 생성 후 불변 */
    @Column(nullable = false, length = 100, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActiveStatus status;

    private SaleProduct(Long brandId, String name, String code) {
        this.brandId = brandId;
        this.name = name;
        this.code = code;
        this.status = ActiveStatus.ACTIVE;
    }

    public static SaleProduct create(Long brandId, String name, String code) {
        return new SaleProduct(brandId, name, code);
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

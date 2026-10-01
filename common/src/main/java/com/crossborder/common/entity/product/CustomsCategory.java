package com.crossborder.common.entity.product;

import com.crossborder.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 통관 품목 분류 (수량 한도는 이 분류 단위로 합산 판정)
 */
@Getter
@Entity
@Table(name = "customs_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomsCategory extends BaseEntity {

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** 1회 통관 수량 한도. null이면 한도 없음 */
    @Column(name = "qty_limit")
    private Integer qtyLimit;

    private CustomsCategory(String code, String name, Integer qtyLimit) {
        this.code = code;
        this.name = name;
        this.qtyLimit = qtyLimit;
    }

    public static CustomsCategory create(String code, String name, Integer qtyLimit) {
        return new CustomsCategory(code, name, qtyLimit);
    }

    public void changeInfo(String name, Integer qtyLimit) {
        this.name = name;
        this.qtyLimit = qtyLimit;
    }

    public boolean hasQtyLimit() {
        return qtyLimit != null;
    }

    /**
     * 같은 분류의 합산 수량이 한도를 넘는지 판정
     */
    public boolean exceedsQtyLimit(int totalQuantity) {
        return hasQtyLimit() && totalQuantity > qtyLimit;
    }
}

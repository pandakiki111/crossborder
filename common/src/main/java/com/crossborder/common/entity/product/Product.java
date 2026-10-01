package com.crossborder.common.entity.product;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 제품 (재고 관리 단위, SKU)
 * <p>
 * 판매가능재고 = physicalStock - allocatedStock (계산값).
 * physicalStock 증감은 반드시 StockMovement 원장 기록과 함께 수행한다.
 */
@Getter
@Entity
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseAuditEntity {

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    /** 내부 재고 관리 코드, 생성 후 불변 */
    @Column(nullable = false, length = 50, updatable = false)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "name_eng", nullable = false, length = 300)
    private String nameEng;

    /** 통관 품목 분류 (null이면 수량 한도 대상 아님) */
    @Column(name = "customs_category_id")
    private Long customsCategoryId;

    @Column(name = "hs_code", length = 20)
    private String hsCode;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(columnDefinition = "CHAR(3)")
    private String currency;

    @Column(length = 50)
    private String barcode;

    @Column(name = "physical_stock", nullable = false)
    private int physicalStock;

    @Column(name = "allocated_stock", nullable = false)
    private int allocatedStock;

    @Column(name = "weight_g", precision = 10, scale = 3)
    private BigDecimal weightG;

    @Column(name = "width_cm", precision = 10, scale = 2)
    private BigDecimal widthCm;

    @Column(name = "length_cm", precision = 10, scale = 2)
    private BigDecimal lengthCm;

    @Column(name = "height_cm", precision = 10, scale = 2)
    private BigDecimal heightCm;

    @Column(length = 50)
    private String origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActiveStatus status;

    @Builder
    private Product(Long brandId, String sku, String name, String nameEng, Long customsCategoryId,
                    String hsCode, BigDecimal unitPrice, String currency, String barcode,
                    BigDecimal weightG, BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm,
                    String origin) {
        this.brandId = brandId;
        this.sku = sku;
        this.name = name;
        this.nameEng = nameEng;
        this.customsCategoryId = customsCategoryId;
        this.hsCode = hsCode;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.barcode = barcode;
        this.weightG = weightG;
        this.widthCm = widthCm;
        this.lengthCm = lengthCm;
        this.heightCm = heightCm;
        this.origin = origin;
        this.physicalStock = 0;
        this.allocatedStock = 0;
        this.status = ActiveStatus.ACTIVE;
    }

    public void changeBasicInfo(String name, String nameEng, String barcode, BigDecimal unitPrice, String currency) {
        this.name = name;
        this.nameEng = nameEng;
        this.barcode = barcode;
        this.unitPrice = unitPrice;
        this.currency = currency;
    }

    public void changeCustomsInfo(Long customsCategoryId, String hsCode, String origin) {
        this.customsCategoryId = customsCategoryId;
        this.hsCode = hsCode;
        this.origin = origin;
    }

    public void changeDimensions(BigDecimal weightG, BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm) {
        this.weightG = weightG;
        this.widthCm = widthCm;
        this.lengthCm = lengthCm;
        this.heightCm = heightCm;
    }

    /**
     * 물리재고 증감 (StockMovement.quantity와 동일한 부호 규칙: 입고 +, 출고 -)
     */
    public void applyPhysicalStockChange(int delta) {
        if (physicalStock + delta < 0) {
            throw new IllegalStateException(
                    "물리재고가 음수가 될 수 없습니다. sku=" + sku + ", 현재=" + physicalStock + ", 변동=" + delta);
        }
        this.physicalStock += delta;
    }

    /**
     * 주문 할당. 마켓 주문은 재고 부족이어도 수신되므로 판매가능재고 음수를 허용한다.
     */
    public void allocate(int quantity) {
        requirePositive(quantity);
        this.allocatedStock += quantity;
    }

    public void deallocate(int quantity) {
        requirePositive(quantity);
        if (allocatedStock < quantity) {
            throw new IllegalStateException(
                    "할당재고보다 많이 해제할 수 없습니다. sku=" + sku + ", 현재=" + allocatedStock + ", 해제=" + quantity);
        }
        this.allocatedStock -= quantity;
    }

    public int getAvailableStock() {
        return physicalStock - allocatedStock;
    }

    public void activate() {
        this.status = ActiveStatus.ACTIVE;
    }

    /**
     * 비활성화. 이 제품을 구성에 포함한 판매상품의 처리(비활성/경고)는 서비스 레이어 책임
     */
    public void deactivate() {
        this.status = ActiveStatus.INACTIVE;
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
    }
}

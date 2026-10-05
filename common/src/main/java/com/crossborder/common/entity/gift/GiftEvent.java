package com.crossborder.common.entity.gift;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사은품 이벤트 (브랜드 단위). 조건(GiftEventCondition)·품목(GiftEventItem)·활성 구간(GiftEventActivePeriod)은 별도 행이고,
 * 판정은 GiftEventEvaluator 하나가 한다.
 * <p>
 * 서비스 레이어 보장: 지급 이력(gift_event_grants)이 있으면 {@link #redefine} 금지 (수정 불가 — 새 이벤트로 등록).
 * 상태는 저장하지 않는다 ({@link GiftEventStatus} 참고).
 */
@Getter
@Entity
@Table(name = "gift_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftEvent extends BaseAuditEntity {

    /** 이벤트 코드 접두 (GEVT-XXXXXXXX) */
    public static final String CODE_PREFIX = "GEVT-";

    /**
     * 이벤트 코드. 서버가 생성하고(GiftEventCodeGenerator) 바뀌지 않는다 — 운영자가 전화·메신저로 부르는 식별자.
     * 등록은 코드 충돌 재시도를 위해 JDBC로 넣으므로 엔티티는 값을 쓰지 않는다.
     */
    @Column(nullable = false, length = 13, insertable = false, updatable = false)
    private String code;

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_basis", nullable = false, length = 20)
    private GiftTimeBasis timeBasis;

    /** 기간 시작 (포함) */
    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** 기간 끝 (미포함) */
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    /** 결제금액 하한 (포함). null = 없음 */
    @Column(name = "amount_min", precision = 12, scale = 2)
    private BigDecimal amountMin;

    /** 결제금액 상한 (미포함). null = 없음 */
    @Column(name = "amount_max", precision = 12, scale = 2)
    private BigDecimal amountMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_mode", nullable = false, length = 10)
    private GiftConditionMode conditionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", nullable = false, length = 20)
    private GiftGrantType grantType;

    @Enumerated(EnumType.STRING)
    @Column(name = "quantity_mode", nullable = false, length = 20)
    private GiftQuantityMode quantityMode;

    @Column(name = "fixed_qty")
    private Integer fixedQty;

    @Column(name = "per_qty_unit")
    private Integer perQtyUnit;

    @Column(name = "per_qty_give")
    private Integer perQtyGive;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GiftAggregation aggregation;

    /**
     * 이벤트 정의 값. 수량 규칙: FIXED면 fixedQty만, PER_QUANTITY면 perQtyUnit·perQtyGive·aggregation만.
     */
    public record Definition(String name, GiftTimeBasis timeBasis, LocalDateTime startsAt, LocalDateTime endsAt,
                             BigDecimal amountMin, BigDecimal amountMax, GiftConditionMode conditionMode,
                             GiftGrantType grantType, GiftQuantityMode quantityMode, Integer fixedQty,
                             Integer perQtyUnit, Integer perQtyGive, GiftAggregation aggregation) {
    }

    private GiftEvent(Long brandId, Definition definition) {
        this.brandId = Objects.requireNonNull(brandId, "브랜드가 없습니다.");
        apply(definition);
    }

    /**
     * @throws IllegalArgumentException 기간·금액·수량 규칙 위반
     */
    public static GiftEvent create(Long brandId, Definition definition) {
        return new GiftEvent(brandId, definition);
    }

    /**
     * 정의 전체 교체. 지급 이력이 없는지는 서비스 책임.
     */
    public void redefine(Definition definition) {
        apply(definition);
    }

    /** 상태 파생. hasOpenPeriod = 열린 활성 구간(active_to NULL)이 있음 */
    public GiftEventStatus statusAt(LocalDateTime now, boolean hasOpenPeriod) {
        if (!now.isBefore(endsAt)) {
            return GiftEventStatus.ENDED;
        }
        return hasOpenPeriod ? GiftEventStatus.ACTIVE : GiftEventStatus.STOPPED;
    }

    private void apply(Definition d) {
        requireText(d.name(), "이벤트명");
        Objects.requireNonNull(d.timeBasis(), "기준 시각(timeBasis)이 없습니다.");
        Objects.requireNonNull(d.conditionMode(), "조건 결합(conditionMode)이 없습니다.");
        Objects.requireNonNull(d.grantType(), "증정 방식(grantType)이 없습니다.");
        Objects.requireNonNull(d.quantityMode(), "수량 방식(quantityMode)이 없습니다.");
        if (d.startsAt() == null || d.endsAt() == null || !d.startsAt().isBefore(d.endsAt())) {
            throw new IllegalArgumentException("기간은 시작 < 끝이어야 합니다. startsAt=" + d.startsAt() + ", endsAt=" + d.endsAt());
        }
        if (d.amountMin() != null && d.amountMin().signum() < 0 || d.amountMax() != null && d.amountMax().signum() <= 0) {
            throw new IllegalArgumentException("결제금액 범위는 0 이상이어야 합니다.");
        }
        if (d.amountMin() != null && d.amountMax() != null && d.amountMin().compareTo(d.amountMax()) >= 0) {
            throw new IllegalArgumentException("결제금액 하한은 상한보다 작아야 합니다. [" + d.amountMin() + ", " + d.amountMax() + ")");
        }
        switch (d.quantityMode()) {
            case FIXED -> {
                if (d.fixedQty() == null || d.fixedQty() < 1
                        || d.perQtyUnit() != null || d.perQtyGive() != null || d.aggregation() != null) {
                    throw new IllegalArgumentException("FIXED는 fixedQty(1 이상)만 지정합니다.");
                }
            }
            case PER_QUANTITY -> {
                if (d.fixedQty() != null || d.perQtyUnit() == null || d.perQtyUnit() < 1
                        || d.perQtyGive() == null || d.perQtyGive() < 1 || d.aggregation() == null) {
                    throw new IllegalArgumentException("PER_QUANTITY는 perQtyUnit·perQtyGive(1 이상)·aggregation만 지정합니다.");
                }
            }
        }
        this.name = d.name().trim();
        this.timeBasis = d.timeBasis();
        this.startsAt = d.startsAt();
        this.endsAt = d.endsAt();
        this.amountMin = d.amountMin();
        this.amountMax = d.amountMax();
        this.conditionMode = d.conditionMode();
        this.grantType = d.grantType();
        this.quantityMode = d.quantityMode();
        this.fixedQty = d.fixedQty();
        this.perQtyUnit = d.perQtyUnit();
        this.perQtyGive = d.perQtyGive();
        this.aggregation = d.aggregation();
    }

    public Definition definition() {
        return new Definition(name, timeBasis, startsAt, endsAt, amountMin, amountMax, conditionMode, grantType,
                quantityMode, fixedQty, perQtyUnit, perQtyGive, aggregation);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "이 없습니다.");
        }
    }
}

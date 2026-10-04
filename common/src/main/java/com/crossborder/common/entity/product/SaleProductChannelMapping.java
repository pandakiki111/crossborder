package com.crossborder.common.entity.product;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매상품-채널 코드 매핑 이력. (channel, brand, code, optionCode, 주문 시각) → 판매상품 1개 확정
 * <p>
 * 브랜드마다 마켓 스토어가 따로라 채널 상품코드는 브랜드 간에 겹칠 수 있다. 유니크·조회 모두 브랜드 단위.
 * <p>
 * 행은 이력이라 고치지 않는다. 판매상품 재지정은 현재 행 마감 + 신규 행(renew), 삭제는 마감(close).
 * 유효 기간은 반열림 구간 [effectiveFrom, effectiveTo)이고, 주문 수집은 ordered_at이 속한 행을 쓴다.
 */
@Getter
@Entity
@Table(name = "sale_product_channel_mappings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleProductChannelMapping extends BaseAuditEntity {

    private static final String NO_OPTION = "";

    /** 최초 매핑의 시작 (기간 시작 없음). 매핑 전에 들어온 매핑안됨 주문도 이 매핑으로 확정된다 */
    public static final LocalDateTime UNBOUNDED_PAST = LocalDateTime.of(1000, 1, 1, 0, 0);

    @Column(name = "sale_product_id", nullable = false, updatable = false)
    private Long saleProductId;

    /** 판매상품 브랜드 복사값 (유니크 키용) */
    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private Long channelId;

    @Column(nullable = false, length = 100, updatable = false)
    private String code;

    /** 옵션 없음은 빈 문자열 (UNIQUE 키에 NULL이 들어가지 않도록) */
    @Column(name = "option_code", nullable = false, length = 30, updatable = false)
    private String optionCode;

    /** 유효 시작 (포함) */
    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDateTime effectiveFrom;

    /** 유효 끝 (미포함). null이면 현재 유효 */
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    private SaleProductChannelMapping(SaleProduct saleProduct, Long channelId, String code, String optionCode,
                                      LocalDateTime effectiveFrom) {
        this.saleProductId = Objects.requireNonNull(saleProduct.getId(), "저장되지 않은 판매상품입니다.");
        this.brandId = saleProduct.getBrandId();
        this.channelId = channelId;
        this.code = code;
        this.optionCode = normalizeOptionCode(optionCode);
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom);
    }

    /**
     * @param effectiveFrom 이 키의 이력이 없으면 UNBOUNDED_PAST, 마감된 이력만 있으면 처리 시각 (서비스가 판단)
     */
    public static SaleProductChannelMapping create(SaleProduct saleProduct, Long channelId, String code,
                                                   String optionCode, LocalDateTime effectiveFrom) {
        return new SaleProductChannelMapping(saleProduct, channelId, code, optionCode, effectiveFrom);
    }

    /**
     * 같은 키를 다른 판매상품(같은 브랜드)으로 재지정. 이 행을 at에 마감하고 at부터 유효한 신규 행을 돌려준다.
     * at 이전 주문(늦게 수집돼도)은 이 행, at 이후 주문은 신규 행으로 매칭된다.
     * <p>
     * 신규 행 저장 전에 이 행의 마감이 먼저 반영(flush)돼야 한다 — 현재 행 유니크(uk_..._current) 때문.
     */
    public SaleProductChannelMapping renew(SaleProduct saleProduct, LocalDateTime at) {
        if (!brandId.equals(saleProduct.getBrandId())) {
            throw new IllegalArgumentException("다른 브랜드 판매상품으로 재지정할 수 없습니다. mappingBrandId=" + brandId
                    + ", saleProductBrandId=" + saleProduct.getBrandId());
        }
        close(at);
        return new SaleProductChannelMapping(saleProduct, channelId, code, optionCode, at);
    }

    /**
     * at에 마감 (매핑 삭제). 행은 남는다 — 마감 전 주문의 재수집에 필요하다.
     */
    public void close(LocalDateTime at) {
        if (!isCurrent()) {
            throw new IllegalStateException("이미 마감된 매핑입니다. id=" + getId() + ", effectiveTo=" + effectiveTo);
        }
        if (!at.isAfter(effectiveFrom)) {
            throw new IllegalStateException("마감 시각은 시작 시각 이후여야 합니다. id=" + getId()
                    + ", effectiveFrom=" + effectiveFrom + ", at=" + at);
        }
        this.effectiveTo = at;
    }

    public boolean isCurrent() {
        return effectiveTo == null;
    }

    /** 반열림 구간 [effectiveFrom, effectiveTo) */
    public boolean covers(LocalDateTime orderedAt) {
        return !orderedAt.isBefore(effectiveFrom) && (effectiveTo == null || orderedAt.isBefore(effectiveTo));
    }

    public boolean hasOption() {
        return !NO_OPTION.equals(optionCode);
    }

    /**
     * 옵션 없음(null·공백)을 빈 문자열로 맞춘다. 매핑 조회 시에도 같은 규칙으로 정규화해야 옵션 없는 매핑이 찾아진다.
     */
    public static String normalizeOptionCode(String optionCode) {
        return optionCode == null || optionCode.isBlank() ? NO_OPTION : optionCode;
    }
}

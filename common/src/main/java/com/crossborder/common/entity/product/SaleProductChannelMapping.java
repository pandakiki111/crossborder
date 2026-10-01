package com.crossborder.common.entity.product;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매상품-채널 코드 매핑. (channel, code, optionCode) → 판매상품 1개 확정
 */
@Getter
@Entity
@Table(name = "sale_product_channel_mappings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleProductChannelMapping extends BaseAuditEntity {

    private static final String NO_OPTION = "";

    @Column(name = "sale_product_id", nullable = false)
    private Long saleProductId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private Long channelId;

    @Column(nullable = false, length = 100)
    private String code;

    /** 옵션 없음은 빈 문자열 (UNIQUE 키에 NULL이 들어가지 않도록) */
    @Column(name = "option_code", nullable = false, length = 30)
    private String optionCode;

    private SaleProductChannelMapping(Long saleProductId, Long channelId, String code, String optionCode) {
        this.saleProductId = saleProductId;
        this.channelId = channelId;
        this.code = code;
        this.optionCode = normalizeOptionCode(optionCode);
    }

    public static SaleProductChannelMapping create(Long saleProductId, Long channelId,
                                                   String code, String optionCode) {
        return new SaleProductChannelMapping(saleProductId, channelId, code, optionCode);
    }

    public void changeChannelCode(String code, String optionCode) {
        this.code = code;
        this.optionCode = normalizeOptionCode(optionCode);
    }

    public void remapTo(Long saleProductId) {
        this.saleProductId = saleProductId;
    }

    public boolean hasOption() {
        return !NO_OPTION.equals(optionCode);
    }

    private static String normalizeOptionCode(String optionCode) {
        return optionCode == null ? NO_OPTION : optionCode;
    }
}

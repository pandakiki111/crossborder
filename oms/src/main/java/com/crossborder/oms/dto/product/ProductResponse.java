package com.crossborder.oms.dto.product;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.product.Product;
import java.math.BigDecimal;

public record ProductResponse(Long id, Long brandId, String sku, String name, String nameEng, Long customsCategoryId,
                              int customsUnitQty, String hsCode, BigDecimal unitPrice, String currency, String barcode,
                              BigDecimal weightG, BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm,
                              String origin, int physicalStock, int allocatedStock, int availableStock,
                              ActiveStatus status) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getBrandId(), p.getSku(), p.getName(), p.getNameEng(),
                p.getCustomsCategoryId(), p.getCustomsUnitQty(), p.getHsCode(), p.getUnitPrice(), p.getCurrency(),
                p.getBarcode(), p.getWeightG(), p.getWidthCm(), p.getLengthCm(), p.getHeightCm(), p.getOrigin(),
                p.getPhysicalStock(), p.getAllocatedStock(), p.getAvailableStock(), p.getStatus());
    }
}

package com.crossborder.oms.service.shipment;

import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.order.SplitReason;
import com.crossborder.common.entity.product.CustomsCategory;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProductItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 분리 계획 (순수 로직, DB 접근 없음).
 * <ol>
 *   <li>브랜드 분리: 유효 항목을 brand_id로 묶는다 — 한 회차 = 단일 브랜드</li>
 *   <li>통관 분류 한도: 브랜드 그룹별로 분류마다 Σ(전개 제품 수량 × customs_unit_qty)가 qty_limit를 넘지 않게
 *       항목을 수량 단위로 순차 배분한다 (항목 id 순, 넘치면 새 회차). bin-packing 최적화는 하지 않는다</li>
 * </ol>
 * 분할 단위는 주문 항목 1개(세트면 구성 전체)다 — shipment_items가 주문 항목 × 수량이라 구성품을 회차별로 쪼갤 수 없다.
 * 항목 1개의 환산수량이 이미 분류 한도를 넘으면 분할로 해결할 수 없어 분리 실패다.
 * 사은품(건별 GIFT_PRODUCT·구성 고정 is_gift)도 전개분이 통관 수량에 포함된다. 분류 없는 제품·한도 없는 분류는 판정 대상이 아니다.
 * <p>
 * split_reason (그룹 단위): 분류 한도로 나뉜 그룹 = CUSTOMS_LIMIT, 그 외 브랜드가 여러 개면 BRAND_SPLIT, 단일 회차면 null.
 * "품목당 24개" 규정은 품목 판별이 데이터로 불가해 분할하지 않는다 — 회차 개수 경고({@link #warnings})로 근사한다.
 */
public final class ShipmentPlanner {

    public record Line(OrderItem item, int quantity) {
    }

    public record PlannedShipment(Long brandId, SplitReason splitReason, List<Line> lines) {

        /** 회차 금액 = Σ(단가 스냅샷 × 배정 수량). 사은품은 단가 0이라 0 기여 */
        public BigDecimal totalAmount() {
            return lines.stream()
                    .map(line -> line.item().getUnitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public int totalQuantity() {
            return lines.stream().mapToInt(Line::quantity).sum();
        }
    }

    /** 성공이면 shipments, 분할 불가면 failure (사유) */
    public record Result(List<PlannedShipment> shipments, String failure) {

        public boolean failed() {
            return failure != null;
        }
    }

    private final Map<Long, List<SaleProductItem>> compositions;
    private final Map<Long, Product> products;
    private final Map<Long, CustomsCategory> categories;

    /**
     * @param compositions 판매상품 id → 구성
     * @param products     전개에 나오는 제품 (id → 제품)
     * @param categories   통관 분류 (id → 분류)
     */
    public ShipmentPlanner(Map<Long, List<SaleProductItem>> compositions, Map<Long, Product> products,
                           Map<Long, CustomsCategory> categories) {
        this.compositions = compositions;
        this.products = products;
        this.categories = categories;
    }

    /** 유효(ORDERED) 항목만 넘긴다 */
    public Result plan(List<OrderItem> items) {
        Map<Long, List<OrderItem>> byBrand = new LinkedHashMap<>();
        items.stream().sorted(Comparator.comparing(OrderItem::getId))
                .forEach(item -> byBrand.computeIfAbsent(item.getBrandId(), k -> new ArrayList<>()).add(item));

        List<PlannedShipment> shipments = new ArrayList<>();
        boolean multiBrand = byBrand.size() > 1;
        for (Map.Entry<Long, List<OrderItem>> group : byBrand.entrySet()) {
            List<List<Line>> bins = new ArrayList<>();
            List<Line> current = new ArrayList<>();
            Map<Long, Integer> sums = new HashMap<>();
            for (OrderItem item : group.getValue()) {
                Map<Long, Integer> perUnit = limitedQuantityPerUnit(item);
                String unsplittable = unsplittable(item, perUnit);
                if (unsplittable != null) {
                    return new Result(List.of(), unsplittable);
                }
                for (int unit = 0; unit < item.getQuantity(); unit++) {
                    if (!current.isEmpty() && exceeds(sums, perUnit)) {
                        bins.add(current);
                        current = new ArrayList<>();
                        sums.clear();
                    }
                    perUnit.forEach((categoryId, qty) -> sums.merge(categoryId, qty, Integer::sum));
                    addUnit(current, item);
                }
            }
            bins.add(current);

            SplitReason reason = bins.size() > 1 ? SplitReason.CUSTOMS_LIMIT : multiBrand ? SplitReason.BRAND_SPLIT : null;
            bins.forEach(lines -> shipments.add(new PlannedShipment(group.getKey(), reason, List.copyOf(lines))));
        }
        return new Result(shipments, null);
    }

    /**
     * "품목당 24개(표준)" 규정 근사 경고. 품목 판별이 불가해 회차 전체 개수(판매상품 단위, 환산 없음)로 본다.
     */
    public static List<String> warnings(int totalQuantity, int warnThreshold) {
        return totalQuantity > warnThreshold
                ? List.of("총 수량 " + totalQuantity + "개 — 품목당 " + warnThreshold + "개 규정 확인 필요")
                : List.of();
    }

    /** 항목 1단위의 한도 있는 분류별 환산수량 */
    private Map<Long, Integer> limitedQuantityPerUnit(OrderItem item) {
        Map<Long, Integer> perUnit = new HashMap<>();
        components(item).forEach((productId, quantity) -> {
            Product product = products.get(productId);
            if (product == null || product.getCustomsCategoryId() == null) {
                return;
            }
            CustomsCategory category = categories.get(product.getCustomsCategoryId());
            if (category != null && category.hasQtyLimit()) {
                perUnit.merge(category.getId(), quantity * product.getCustomsUnitQty(), Integer::sum);
            }
        });
        return perUnit;
    }

    /** 항목 1단위의 제품별 수량 (판매상품은 구성, 건별 사은품은 제품 1개) */
    private Map<Long, Integer> components(OrderItem item) {
        Map<Long, Integer> components = new LinkedHashMap<>();
        if (item.getItemType() == OrderItemType.GIFT_PRODUCT) {
            components.put(item.getProductId(), 1);
            return components;
        }
        for (SaleProductItem component : compositions.getOrDefault(item.getSaleProductId(), List.of())) {
            components.merge(component.getProductId(), component.getQuantity(), Integer::sum);
        }
        return components;
    }

    private boolean exceeds(Map<Long, Integer> sums, Map<Long, Integer> perUnit) {
        for (Map.Entry<Long, Integer> unit : perUnit.entrySet()) {
            if (sums.getOrDefault(unit.getKey(), 0) + unit.getValue() > categories.get(unit.getKey()).getQtyLimit()) {
                return true;
            }
        }
        return false;
    }

    /** 1단위가 이미 한도를 넘으면 사유 (분류에서 환산수량이 가장 큰 제품의 SKU) */
    private String unsplittable(OrderItem item, Map<Long, Integer> perUnit) {
        for (Map.Entry<Long, Integer> unit : perUnit.entrySet()) {
            CustomsCategory category = categories.get(unit.getKey());
            if (unit.getValue() > category.getQtyLimit()) {
                String sku = components(item).entrySet().stream()
                        .filter(e -> products.get(e.getKey()) != null
                                && category.getId().equals(products.get(e.getKey()).getCustomsCategoryId()))
                        .max(Comparator.comparingInt(e -> e.getValue() * products.get(e.getKey()).getCustomsUnitQty()))
                        .map(e -> products.get(e.getKey()).getSku())
                        .orElse("?");
                return "단일 상품이 통관 한도 초과: " + sku + ", 환산수량 " + unit.getValue() + " > 한도 " + category.getQtyLimit()
                        + " (orderItemId=" + item.getId() + ")";
            }
        }
        return null;
    }

    private static void addUnit(List<Line> lines, OrderItem item) {
        if (!lines.isEmpty() && lines.getLast().item() == item) {
            Line last = lines.removeLast();
            lines.add(new Line(item, last.quantity() + 1));
        } else {
            lines.add(new Line(item, 1));
        }
    }
}

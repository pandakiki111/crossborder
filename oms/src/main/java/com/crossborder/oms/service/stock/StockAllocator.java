package com.crossborder.oms.service.stock;

import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.product.SaleProductItem;
import com.crossborder.oms.repository.SaleProductItemRepository;
import com.crossborder.oms.service.support.InClause;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 주문 재고 할당 (products.allocated_stock).
 * <ul>
 *   <li>전개: SALE_PRODUCT 항목은 판매상품 구성(sale_product_items) × 주문수량으로 제품 단위 수량을 낸다.
 *       구성 고정 사은품(is_gift)도 재고 차감 대상이라 포함한다. GIFT_PRODUCT 항목은 제품 직접.</li>
 *   <li>증감: 원자적 UPDATE(allocated_stock = allocated_stock + ?)로 반영한다. 판매가능재고 음수 허용 —
 *       마켓 주문은 재고 부족이어도 수신되므로 한도 검사를 하지 않는다 (Product.allocate와 같은 규칙).</li>
 *   <li>동시성: 호출 측이 {@link #lockKeys} 키로 분산 락을 잡은 상태에서, 트랜잭션 안에서 호출한다.
 *       allocated는 원장(stock_movements) 대상이 아니다.</li>
 * </ul>
 * 구성은 주문 이력이 생기면 바뀌지 않는다는 전제(§4)라, 등록 시점과 이후 전개 결과가 같다.
 */
@Component
public class StockAllocator {

    private static final String LOCK_KEY_PREFIX = "stock:product:";
    private static final String INCREASE_ALLOCATED =
            "UPDATE products SET allocated_stock = allocated_stock + ? WHERE id = ?";

    private final SaleProductItemRepository saleProductItemRepository;
    private final JdbcTemplate jdbcTemplate;

    public StockAllocator(SaleProductItemRepository saleProductItemRepository, JdbcTemplate jdbcTemplate) {
        this.saleProductItemRepository = saleProductItemRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 판매상품별 구성 (전개 입력) */
    public Map<Long, List<SaleProductItem>> loadCompositions(Collection<Long> saleProductIds) {
        Map<Long, List<SaleProductItem>> compositions = new HashMap<>();
        for (List<Long> chunk : InClause.partition(Set.copyOf(saleProductIds))) {
            saleProductItemRepository.findBySaleProductIdIn(chunk).forEach(item ->
                    compositions.computeIfAbsent(item.getSaleProductId(), k -> new ArrayList<>()).add(item));
        }
        return compositions;
    }

    /**
     * 유효(ORDERED) 항목을 제품 단위 수량으로 전개한다.
     *
     * @throws IllegalStateException 판매상품 미확정(매핑안됨) 항목이 있음 — 매핑안됨 주문은 할당 대상이 아니다
     */
    public static Map<Long, Integer> expand(Collection<OrderItem> items, Map<Long, List<SaleProductItem>> compositions) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (OrderItem item : items) {
            if (!item.isOrdered()) {
                continue;
            }
            if (item.getItemType() == OrderItemType.GIFT_PRODUCT) {
                quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
                continue;
            }
            if (!item.isMapped()) {
                throw new IllegalStateException("판매상품이 확정되지 않은 항목은 전개할 수 없습니다. orderItemId=" + item.getId());
            }
            for (SaleProductItem component : compositions.getOrDefault(item.getSaleProductId(), List.of())) {
                quantities.merge(component.getProductId(), component.getQuantity() * item.getQuantity(), Integer::sum);
            }
        }
        return quantities;
    }

    /** 여러 주문의 전개 결과 합산 */
    public static Map<Long, Integer> sum(Collection<Map<Long, Integer>> allocations) {
        Map<Long, Integer> total = new TreeMap<>();
        allocations.forEach(allocation -> allocation.forEach((productId, qty) -> total.merge(productId, qty, Integer::sum)));
        return total;
    }

    public static List<String> lockKeys(Collection<Long> productIds) {
        return productIds.stream().sorted().map(id -> LOCK_KEY_PREFIX + id).collect(Collectors.toList());
    }

    /**
     * allocated_stock 증가. 트랜잭션 안에서, lockKeys 락을 잡은 상태로 호출한다.
     */
    public void increase(Map<Long, Integer> quantities) {
        List<Object[]> rows = quantities.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new Object[]{e.getValue(), e.getKey()})
                .toList();
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(INCREASE_ALLOCATED, rows);
        }
    }

}

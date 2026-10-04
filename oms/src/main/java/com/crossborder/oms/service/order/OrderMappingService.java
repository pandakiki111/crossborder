package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.common.entity.product.SaleProductChannelMapping;
import com.crossborder.common.entity.product.SaleProductItem;
import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.SaleProductChannelMappingRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import com.crossborder.oms.service.stock.StockAllocator;
import com.crossborder.oms.service.support.InClause;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매핑안됨 주문 항목 확정. 상품 매핑이 등록된 뒤 호출한다 (ChannelMappingService).
 * <p>
 * 매핑은 브랜드 단위 이력이라, 같은 (채널, 브랜드, 상품코드, 옵션코드)의 매핑안됨 항목을
 * 그 주문의 ordered_at이 속한 기간의 매핑으로 확정한다. 어느 기간에도 속하지 않는 항목은 매핑안됨으로 남는다.
 * 남은 유효 매핑안됨 항목이 없는 주문은 매핑안됨 플래그를 해제한다 (상태는 그대로라 상태 이력 없음).
 * <p>
 * 매핑이 완료된 주문은 이때 처음으로 주문 전체를 재고 할당한다 (매핑안됨 주문은 등록 시 할당하지 않음).
 * 호출 측 트랜잭션 안에서 제품 락을 잡고, 커밋·롤백 후 해제한다. 락 타임아웃이면 LockAcquisitionException으로
 * 매핑 등록 트랜잭션 전체가 롤백된다 (409).
 */
@Service
public class OrderMappingService {

    private final SaleProductChannelMappingRepository mappingRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final SaleProductRepository saleProductRepository;
    private final StockAllocator stockAllocator;
    private final DistributedLockManager lockManager;

    public OrderMappingService(SaleProductChannelMappingRepository mappingRepository,
                               OrderItemRepository orderItemRepository, OrderRepository orderRepository,
                               SaleProductRepository saleProductRepository, StockAllocator stockAllocator,
                               DistributedLockManager lockManager) {
        this.mappingRepository = mappingRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.saleProductRepository = saleProductRepository;
        this.stockAllocator = stockAllocator;
        this.lockManager = lockManager;
    }

    public record Result(int mappedItemCount, int completedOrderCount) {
    }

    @Transactional
    public Result resolveUnmappedItems(Long channelId, Long brandId, String channelProductCode,
                                       String channelOptionCode) {
        String optionCode = SaleProductChannelMapping.normalizeOptionCode(channelOptionCode);
        List<SaleProductChannelMapping> history = mappingRepository
                .findByChannelIdAndBrandIdAndCodeAndOptionCodeOrderByEffectiveFromAsc(
                        channelId, brandId, channelProductCode, optionCode);
        List<OrderItem> items = history.isEmpty()
                ? List.of()
                : orderItemRepository.findUnmapped(channelId, brandId, channelProductCode, optionCode);
        if (items.isEmpty()) {
            return new Result(0, 0);
        }

        Map<Long, Order> orders = loadAll(orderRepository::findAllById, Order::getId,
                items.stream().map(OrderItem::getOrderId).toList());
        Map<Long, SaleProduct> saleProducts = loadAll(saleProductRepository::findAllById, SaleProduct::getId,
                history.stream().map(SaleProductChannelMapping::getSaleProductId).toList());

        int mapped = 0;
        Set<Long> touchedOrderIds = new HashSet<>();
        for (OrderItem item : items) {
            Order order = orders.get(item.getOrderId());
            for (SaleProductChannelMapping mapping : history) {
                if (mapping.covers(order.getOrderedAt())) {
                    item.mapTo(saleProducts.get(mapping.getSaleProductId()));
                    touchedOrderIds.add(order.getId());
                    mapped++;
                    break;
                }
            }
        }
        if (touchedOrderIds.isEmpty()) {
            return new Result(0, 0);
        }

        orderItemRepository.flush();
        Set<Long> stillUnmapped = collect(touchedOrderIds, orderItemRepository::findOrderIdsWithUnmapped);
        List<Order> completed = new ArrayList<>();
        for (Long orderId : touchedOrderIds) {
            Order order = orders.get(orderId);
            if (order.isMappingPending() && !stillUnmapped.contains(orderId)) {
                order.completeMapping();
                completed.add(order);
            }
        }
        allocate(completed);
        return new Result(mapped, completed.size());
    }

    /** 매핑이 끝난 주문 전체 할당 + allocated_at 기록 (같은 트랜잭션) */
    private void allocate(List<Order> orders) {
        if (orders.isEmpty()) {
            return;
        }
        List<OrderItem> items = new ArrayList<>();
        for (List<Long> chunk : InClause.partition(orders.stream().map(Order::getId).toList())) {
            items.addAll(orderItemRepository.findByOrderIdIn(chunk));
        }
        Map<Long, List<SaleProductItem>> compositions = stockAllocator.loadCompositions(items.stream()
                .map(OrderItem::getSaleProductId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, Integer> allocation = StockAllocator.expand(items, compositions);

        lockManager.lockUntilTransactionEnd(StockAllocator.lockKeys(allocation.keySet()));
        stockAllocator.increase(allocation);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        orders.forEach(order -> order.markAllocated(now));
    }

    private static Set<Long> collect(Collection<Long> ids, Function<List<Long>, List<Long>> query) {
        Set<Long> result = new HashSet<>();
        for (List<Long> chunk : InClause.partition(ids)) {
            result.addAll(query.apply(chunk));
        }
        return result;
    }

    private static <T> Map<Long, T> loadAll(Function<List<Long>, List<T>> finder, Function<T, Long> idOf,
                                            Collection<Long> ids) {
        Map<Long, T> loaded = new HashMap<>();
        for (List<Long> chunk : InClause.partition(new HashSet<>(ids))) {
            finder.apply(chunk).forEach(entity -> loaded.put(idOf.apply(entity), entity));
        }
        return loaded;
    }
}

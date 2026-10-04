package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.infra.jpa.AuditorContext;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import com.crossborder.oms.service.support.InClause;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 주문 등록 본체. 엑셀 시딩·마켓 수집(대량)과 단건 등록이 모두 이 경로를 쓴다.
 * <p>
 * 흐름: 사전 일괄 조회(중복·브랜드·판매상품·사은품 제품) → 엔티티 팩토리로 주문 조립(도메인 규칙 검증)
 * → OrderBatchWriter가 묶음 단위 JDBC batch로 저장. 조회·저장 모두 주문 수와 무관하게 왕복 횟수가 거의 일정하다.
 * <p>
 * 이 클래스는 트랜잭션을 걸지 않는다. 커밋 단위는 OrderBatchWriter의 묶음이다.
 * 마켓 수집처럼 입력을 먼저 쌓아 두는 경로(예: Redis 원장)도 쌓인 커맨드를 registerAll로 넘기면 된다.
 */
@Service
public class OrderRegistrationService {

    private final OrderRepository orderRepository;
    private final SaleProductRepository saleProductRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderBatchWriter orderBatchWriter;
    private final Long systemUserId;

    public OrderRegistrationService(OrderRepository orderRepository, SaleProductRepository saleProductRepository,
                                    ProductRepository productRepository, BrandRepository brandRepository, OrderNoGenerator orderNoGenerator,
                                    OrderBatchWriter orderBatchWriter,
                                    @Value("${crossborder.audit.system-user-id:1}") Long systemUserId) {
        this.orderRepository = orderRepository;
        this.saleProductRepository = saleProductRepository;
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.orderBatchWriter = orderBatchWriter;
        this.systemUserId = systemUserId;
    }

    public boolean isRegistered(Long salesChannelId, String channelOrderNo) {
        return orderRepository.existsBySalesChannelIdAndChannelOrderNo(salesChannelId, channelOrderNo);
    }

    /**
     * 단건 등록.
     *
     * @throws DuplicateOrderException    같은 (채널, 채널주문번호) 주문이 이미 있음
     * @throws OrderRegistrationException 등록 불가
     */
    public OrderRegistrationResult register(OrderRegistrationCommand command) {
        OrderRegistrationResult result = registerAll(List.of(command)).getFirst();
        return switch (result.status()) {
            case REGISTERED -> result;
            case DUPLICATE -> throw new DuplicateOrderException(command.salesChannelId(), command.channelOrderNo());
            case FAILED -> throw new OrderRegistrationException(result.message());
        };
    }

    /**
     * 대량 등록. 한 주문 = 단일 브랜드, 회사는 브랜드에서 정한다. 매핑 없는 항목이 있으면 매핑안됨으로 등록한다.
     * 한 주문의 실패가 다른 주문에 영향을 주지 않는다.
     *
     * @return commands와 같은 순서의 결과
     */
    public List<OrderRegistrationResult> registerAll(List<OrderRegistrationCommand> commands) {
        Long userId = AuditorContext.get().orElse(systemUserId);
        Map<Long, Set<String>> existing = findExisting(commands);
        Map<Long, Brand> brands = loadAll(brandRepository::findAllById, Brand::getId,
                commands.stream().map(OrderRegistrationCommand::brandId));
        Map<Long, SaleProduct> saleProducts = loadAll(saleProductRepository::findAllById, SaleProduct::getId,
                allItems(commands).map(OrderRegistrationCommand.Item::saleProductId));
        Map<Long, Product> giftProducts = loadAll(productRepository::findAllById, Product::getId,
                allItems(commands).map(OrderRegistrationCommand.Item::giftProductId));

        OrderRegistrationResult[] results = new OrderRegistrationResult[commands.size()];
        List<OrderDraft> drafts = new ArrayList<>();
        List<Integer> draftIndexes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < commands.size(); i++) {
            OrderRegistrationCommand command = commands.get(i);
            boolean firstInRequest = seen.add(command.salesChannelId() + "\u0000" + command.channelOrderNo());
            if (!firstInRequest || existing.getOrDefault(command.salesChannelId(), Set.of())
                    .contains(command.channelOrderNo())) {
                results[i] = OrderRegistrationResult.duplicate();
                continue;
            }
            try {
                drafts.add(toDraft(command, brands, saleProducts, giftProducts));
                draftIndexes.add(i);
            } catch (OrderRegistrationException | IllegalArgumentException e) {
                // IllegalArgumentException: 엔티티 팩토리의 도메인 규칙 위반 (수량, 브랜드 불일치 등)
                results[i] = OrderRegistrationResult.failed(e.getMessage());
            }
        }

        List<OrderRegistrationResult> written = orderBatchWriter.write(drafts, userId);
        for (int i = 0; i < written.size(); i++) {
            results[draftIndexes.get(i)] = written.get(i);
        }
        return List.of(results);
    }

    private OrderDraft toDraft(OrderRegistrationCommand command, Map<Long, Brand> brands,
                               Map<Long, SaleProduct> saleProducts, Map<Long, Product> giftProducts) {
        Brand brand = brands.get(command.brandId());
        if (brand == null) {
            throw new OrderRegistrationException("브랜드가 없습니다. brandId=" + command.brandId());
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new OrderRegistrationException("주문 항목이 없습니다.");
        }
        Order order = Order.builder()
                .orderNo(orderNoGenerator.generate())
                .salesChannelId(command.salesChannelId())
                .channelOrderNo(command.channelOrderNo())
                .companyId(brand.getCompanyId())
                .totalItemAmount(command.totalItemAmount())
                .paidAmount(command.paidAmount())
                .currency(command.currency())
                .ordererName(command.ordererName())
                .receiverName(command.receiverName())
                .receiverPhone(command.receiverPhone())
                .receiverZipcode(command.receiverZipcode())
                .receiverAddress(command.receiverAddress())
                .deliveryMemo(command.deliveryMemo())
                .orderedAt(command.orderedAt())
                .mappingPending(command.items().stream().anyMatch(item -> !item.isMapped()))
                .build();

        List<OrderItem> items = new ArrayList<>(command.items().size());
        for (OrderRegistrationCommand.Item item : command.items()) {
            // orderId는 저장 시 확정 (OrderBatchWriter)
            if (item.isGift()) {
                Product product = giftProducts.get(item.giftProductId());
                if (product == null) {
                    throw new OrderRegistrationException("사은품 제품이 없습니다. productId=" + item.giftProductId());
                }
                items.add(OrderItem.ofChannelGift(null, brand.getId(), item.channelProductCode(),
                        item.channelOptionCode(), product, item.quantity(), item.unitPrice()));
                continue;
            }
            SaleProduct saleProduct = null;
            if (item.isMapped()) {
                saleProduct = saleProducts.get(item.saleProductId());
                if (saleProduct == null) {
                    throw new OrderRegistrationException("판매상품이 없습니다. saleProductId=" + item.saleProductId());
                }
            }
            items.add(OrderItem.ofChannelProduct(null, brand.getId(), item.channelProductCode(),
                    item.channelOptionCode(), saleProduct, item.quantity(), item.unitPrice()));
        }
        return new OrderDraft(order, items);
    }

    private static java.util.stream.Stream<OrderRegistrationCommand.Item> allItems(
            List<OrderRegistrationCommand> commands) {
        return commands.stream().filter(c -> c.items() != null).flatMap(c -> c.items().stream());
    }

    /** 채널별 기존 채널주문번호 (IN 조회) */
    private Map<Long, Set<String>> findExisting(List<OrderRegistrationCommand> commands) {
        Map<Long, Set<String>> requested = commands.stream().collect(Collectors.groupingBy(
                OrderRegistrationCommand::salesChannelId,
                Collectors.mapping(OrderRegistrationCommand::channelOrderNo, Collectors.toSet())));
        Map<Long, Set<String>> existing = new HashMap<>();
        requested.forEach((channelId, channelOrderNos) -> {
            for (List<String> chunk : InClause.partition(channelOrderNos)) {
                existing.computeIfAbsent(channelId, k -> new HashSet<>())
                        .addAll(orderRepository.findExistingChannelOrderNos(channelId, chunk));
            }
        });
        return existing;
    }

    private static <T> Map<Long, T> loadAll(Function<Collection<Long>, List<T>> finder, Function<T, Long> idOf,
                                            java.util.stream.Stream<Long> ids) {
        Set<Long> distinct = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, T> loaded = new HashMap<>();
        for (List<Long> chunk : InClause.partition(distinct)) {
            finder.apply(chunk).forEach(entity -> loaded.put(idOf.apply(entity), entity));
        }
        return loaded;
    }
}

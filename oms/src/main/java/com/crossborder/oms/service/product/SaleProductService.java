package com.crossborder.oms.service.product;

import com.crossborder.common.entity.product.CustomsCategory;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.common.entity.product.SaleProductChannelMapping;
import com.crossborder.common.entity.product.SaleProductItem;
import com.crossborder.oms.dto.product.CompositionItemRequest;
import com.crossborder.oms.dto.product.SaleProductCreateRequest;
import com.crossborder.oms.dto.product.SaleProductRenewRequest;
import com.crossborder.oms.dto.product.SaleProductRenewResponse;
import com.crossborder.oms.dto.product.SaleProductResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.CustomsCategoryRepository;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.SaleProductChannelMappingRepository;
import com.crossborder.oms.repository.SaleProductItemRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.service.support.BrandWriteGuard;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매상품 관리 — ADMIN·COMPANY_STAFF·BRAND_STAFF(자기 브랜드). §4 규칙의 서비스 구현.
 * <ul>
 *   <li>등록: 구성 포함 원자 등록. code는 브랜드 안에서 유니크(V10)·생성 후 불변. 구성 제품은 같은 브랜드의 ACTIVE 제품</li>
 *   <li>수정: 이름만</li>
 *   <li>구성 변경: 주문 이력(취소 항목 포함)이 없을 때만. 있으면 409 — 지연 전개의 안전 전제(구성 불변)를 지킨다.
 *       새 판매상품으로 등록 후 매핑을 이전하는 리뉴얼 액션을 안내한다</li>
 *   <li>리뉴얼: [새 판매상품 생성(새 code·구성) → 현재 채널 매핑 일괄 재지정(마감 + 신규, 이력 규칙) → 구 상품 INACTIVE]를
 *       한 트랜잭션으로. 재지정 시각 이후 주문만 새 판매상품으로 매칭된다 (이전 주문·늦게 수집되는 이전 주문은 구 상품)</li>
 *   <li>통관 사전 경고: 판매상품 1개의 분류별 환산 수량(Σ 구성 수량 × customs_unit_qty)이 분류 한도를 넘으면 경고 —
 *       이 상품이 든 주문 항목은 회차를 나눌 수 없어 분리가 실패한다 (분할 불가 상품 사전 고지)</li>
 *   <li>비활성화: 현재 채널 매핑이 남아 있어도 허용하되 경고 — 매핑이 살아 있으면 이 판매상품으로 주문이 계속 매칭된다</li>
 *   <li>비활성 브랜드: 등록·수정·구성 변경·리뉴얼·활성화 409. 비활성화는 정리 행위라 허용</li>
 * </ul>
 */
@Service
@Transactional
public class SaleProductService {

    private static final String COMPOSITION_LOCKED =
            "주문 이력이 있는 판매상품은 구성을 바꿀 수 없습니다. 새 판매상품으로 등록 후 매핑을 이전하세요 (리뉴얼 액션: POST /api/sale-products/{id}/renew).";

    private final SaleProductRepository saleProductRepository;
    private final SaleProductItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final CustomsCategoryRepository customsCategoryRepository;
    private final SaleProductChannelMappingRepository mappingRepository;
    private final OrderItemRepository orderItemRepository;
    private final ScopePolicy scopePolicy;
    private final BrandWriteGuard brandWriteGuard;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public SaleProductService(SaleProductRepository saleProductRepository, SaleProductItemRepository itemRepository,
                              ProductRepository productRepository, CustomsCategoryRepository customsCategoryRepository,
                              SaleProductChannelMappingRepository mappingRepository,
                              OrderItemRepository orderItemRepository, ScopePolicy scopePolicy,
                              BrandWriteGuard brandWriteGuard, JdbcTemplate jdbcTemplate, Clock clock) {
        this.saleProductRepository = saleProductRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.customsCategoryRepository = customsCategoryRepository;
        this.mappingRepository = mappingRepository;
        this.orderItemRepository = orderItemRepository;
        this.scopePolicy = scopePolicy;
        this.brandWriteGuard = brandWriteGuard;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * @throws ConflictException 비활성 브랜드 / 브랜드 안 code 중복
     */
    public SaleProductResponse create(SaleProductCreateRequest r, AuthenticatedUser user) {
        scopePolicy.requireBrand(r.brandId(), user);
        brandWriteGuard.requireWritable(r.brandId());
        SaleProduct saleProduct = createWithComposition(r.brandId(), r.code(), r.name(), r.items());
        return toResponse(saleProduct, customsWarnings(saleProduct.getId()));
    }

    @Transactional(readOnly = true)
    public SaleProductResponse get(Long saleProductId, AuthenticatedUser user) {
        return toResponse(require(saleProductId, user), List.of());
    }

    /** brandId 없으면 스코프 안 전체 */
    @Transactional(readOnly = true)
    public List<SaleProductResponse> list(Long brandId, AuthenticatedUser user) {
        List<SaleProduct> saleProducts;
        if (brandId != null) {
            scopePolicy.requireBrand(brandId, user);
            saleProducts = saleProductRepository.findByBrandIdInOrderByIdAsc(List.of(brandId));
        } else {
            saleProducts = scopePolicy.accessibleBrandIds(user)
                    .map(saleProductRepository::findByBrandIdInOrderByIdAsc)
                    .orElseGet(saleProductRepository::findAll);
        }
        return saleProducts.stream().map(sp -> toResponse(sp, List.of())).toList();
    }

    public SaleProductResponse rename(Long saleProductId, String name, AuthenticatedUser user) {
        SaleProduct saleProduct = requireWritable(saleProductId, user);
        saleProduct.changeName(name.trim());
        return toResponse(saleProduct, List.of());
    }

    /**
     * @throws ConflictException 주문 이력 있음 (리뉴얼 안내)
     */
    public SaleProductResponse changeComposition(Long saleProductId, List<CompositionItemRequest> items,
                                                 AuthenticatedUser user) {
        SaleProduct saleProduct = requireWritable(saleProductId, user);
        if (orderItemRepository.existsBySaleProductId(saleProductId)) {
            throw new ConflictException(COMPOSITION_LOCKED);
        }
        List<SaleProductItem> composition = toComposition(saleProduct, items);
        itemRepository.deleteBySaleProductId(saleProductId);
        itemRepository.saveAllAndFlush(composition);
        return toResponse(saleProduct, customsWarnings(saleProductId));
    }

    /**
     * 리뉴얼 액션 (한 트랜잭션). 새 판매상품은 구 상품과 같은 브랜드.
     *
     * @throws ConflictException 구 상품 비활성 / 새 code 중복 / 비활성 브랜드
     */
    public SaleProductRenewResponse renew(Long saleProductId, SaleProductRenewRequest r, AuthenticatedUser user) {
        SaleProduct previous = requireWritable(saleProductId, user);
        if (!previous.isActive()) {
            throw new ConflictException("비활성 판매상품은 리뉴얼할 수 없습니다. saleProductId=" + saleProductId);
        }
        String name = r.name() == null || r.name().isBlank() ? previous.getName() : r.name();
        SaleProduct renewed = createWithComposition(previous.getBrandId(), r.code(), name, r.items());

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        List<SaleProductChannelMapping> current = mappingRepository.findCurrentBySaleProductIdForUpdate(saleProductId);
        List<SaleProductChannelMapping> next = new ArrayList<>();
        for (SaleProductChannelMapping mapping : current) {
            next.add(mapping.renew(renewed, now));
        }
        // 마감(UPDATE)을 신규(INSERT)보다 먼저 반영 — 현재 행 유니크(uk_..._current)
        mappingRepository.flush();
        mappingRepository.saveAllAndFlush(next);
        previous.deactivate();

        List<String> warnings = new ArrayList<>(customsWarnings(renewed.getId()));
        warnings.addAll(eventConditionWarnings(previous));
        return new SaleProductRenewResponse(previous.getId(), toResponse(renewed, warnings), next.size());
    }

    /** 비활성화 — 정리 행위라 비활성 브랜드에서도 허용. 현재 매핑이 남아 있으면 응답에 경고 */
    public SaleProductResponse deactivate(Long saleProductId, AuthenticatedUser user) {
        SaleProduct saleProduct = require(saleProductId, user);
        saleProduct.deactivate();
        return toResponse(saleProduct, remainingMappingWarnings(saleProductId));
    }

    public SaleProductResponse activate(Long saleProductId, AuthenticatedUser user) {
        SaleProduct saleProduct = requireWritable(saleProductId, user);
        saleProduct.activate();
        return toResponse(saleProduct, List.of());
    }

    // ---------------------------------------------------------------- 내부

    private SaleProduct createWithComposition(Long brandId, String code, String name, List<CompositionItemRequest> items) {
        String trimmed = code.trim();
        if (saleProductRepository.existsByBrandIdAndCode(brandId, trimmed)) {
            throw new ConflictException("브랜드 안에 이미 있는 판매상품코드입니다: " + trimmed);
        }
        SaleProduct saleProduct = saleProductRepository.saveAndFlush(SaleProduct.create(brandId, name.trim(), trimmed));
        itemRepository.saveAllAndFlush(toComposition(saleProduct, items));
        return saleProduct;
    }

    /** 구성: 같은 브랜드의 ACTIVE 제품, (제품, 사은품 여부) 중복 없음, 구매 구성품 1개 이상 */
    private List<SaleProductItem> toComposition(SaleProduct saleProduct, List<CompositionItemRequest> items) {
        Map<Long, Product> products = productRepository.findAllById(items.stream().map(CompositionItemRequest::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Set<String> seen = new HashSet<>();
        List<SaleProductItem> composition = new ArrayList<>();
        for (CompositionItemRequest item : items) {
            Product product = products.get(item.productId());
            if (product == null || !product.getBrandId().equals(saleProduct.getBrandId())) {
                throw new InvalidRequestException("판매상품과 같은 브랜드의 제품이 아닙니다. productId=" + item.productId());
            }
            if (!product.isActive()) {
                throw new InvalidRequestException("비활성 제품은 구성에 넣을 수 없습니다. sku=" + product.getSku());
            }
            if (!seen.add(item.productId() + ":" + item.gift())) {
                throw new InvalidRequestException("같은 제품이 구성에 두 번 들어 있습니다. sku=" + product.getSku());
            }
            composition.add(item.gift()
                    ? SaleProductItem.createGift(saleProduct.getId(), product.getId(), item.quantity())
                    : SaleProductItem.create(saleProduct.getId(), product.getId(), item.quantity()));
        }
        if (items.stream().allMatch(CompositionItemRequest::gift)) {
            throw new InvalidRequestException("구성에는 사은품이 아닌 제품이 1개 이상 있어야 합니다.");
        }
        return composition;
    }

    /**
     * 판매상품 1개의 통관 분류별 환산 수량이 분류 한도를 넘으면 경고. 분리 규칙(ShipmentPlanner)은 주문 항목 1개를 쪼개지
     * 않으므로, 이 상품 1개만으로 한도를 넘으면 분리가 실패한다.
     */
    private List<String> customsWarnings(Long saleProductId) {
        List<SaleProductItem> composition = itemRepository.findBySaleProductIdOrderByIdAsc(saleProductId);
        Map<Long, Product> products = productRepository.findAllById(
                composition.stream().map(SaleProductItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Integer> byCategory = new TreeMap<>();
        for (SaleProductItem item : composition) {
            Product product = products.get(item.getProductId());
            if (product.getCustomsCategoryId() != null) {
                byCategory.merge(product.getCustomsCategoryId(), item.getQuantity() * product.getCustomsUnitQty(),
                        Integer::sum);
            }
        }
        if (byCategory.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomsCategory> categories = customsCategoryRepository.findAllById(byCategory.keySet()).stream()
                .collect(Collectors.toMap(CustomsCategory::getId, Function.identity()));
        List<String> warnings = new ArrayList<>();
        byCategory.forEach((categoryId, quantity) -> {
            CustomsCategory category = categories.get(categoryId);
            if (category.exceedsQtyLimit(quantity)) {
                warnings.add("통관 분류 %s(%s) 환산 수량 %d > 한도 %d — 이 판매상품 1개만으로 한도를 넘어 회차를 나눌 수 없습니다 (분리 실패)"
                        .formatted(category.getCode(), category.getName(), quantity, category.getQtyLimit()));
            }
        });
        return warnings;
    }

    /** 리뉴얼로 code가 바뀌면 구 code를 조건으로 쓰는 진행 중 이벤트는 새 상품 주문에 걸리지 않는다 — 운영자에게 알린다 */
    private List<String> eventConditionWarnings(SaleProduct previous) {
        return jdbcTemplate.queryForList("""
                        SELECT DISTINCT CONCAT('[', e.code, '] ', e.name) FROM gift_events e
                        JOIN gift_event_conditions c ON c.gift_event_id = e.id
                        WHERE e.brand_id = ? AND e.ends_at > ? AND c.target_type = 'SALE_PRODUCT' AND c.sale_product_code = ?
                        """, String.class, previous.getBrandId(), LocalDateTime.now(clock), previous.getCode())
                .stream()
                .map(event -> "사은품 이벤트 " + event + "의 조건이 구 판매상품코드(" + previous.getCode()
                        + ")라 리뉴얼 이후 주문에는 걸리지 않습니다 — 새 이벤트로 등록하세요")
                .toList();
    }

    /** 매핑은 판매상품 상태를 보지 않고 매칭하므로(이력 규칙), 비활성 상품에 남은 현재 매핑을 알린다 */
    private List<String> remainingMappingWarnings(Long saleProductId) {
        List<SaleProductChannelMapping> current = mappingRepository.findCurrentBySaleProductId(saleProductId);
        if (current.isEmpty()) {
            return List.of();
        }
        return List.of("현재 채널 매핑 %d건이 남아 있어 이 판매상품으로 주문이 계속 매칭됩니다 — 매핑을 다른 상품으로 바꾸거나 삭제하세요: %s"
                .formatted(current.size(), current.stream()
                        .map(m -> "channelId=" + m.getChannelId() + " " + m.getCode()
                                + (m.getOptionCode().isEmpty() ? "" : "/" + m.getOptionCode()))
                        .collect(Collectors.joining(", "))));
    }

    private SaleProductResponse toResponse(SaleProduct saleProduct, List<String> warnings) {
        List<SaleProductItem> composition = itemRepository.findBySaleProductIdOrderByIdAsc(saleProduct.getId());
        Map<Long, String> skus = productRepository.findAllById(composition.stream().map(SaleProductItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getSku));
        return new SaleProductResponse(saleProduct.getId(), saleProduct.getBrandId(), saleProduct.getCode(),
                saleProduct.getName(), saleProduct.getStatus(),
                composition.stream().map(i -> new SaleProductResponse.Item(i.getProductId(), skus.get(i.getProductId()),
                        i.getQuantity(), i.isGift())).toList(),
                warnings);
    }

    private SaleProduct requireWritable(Long saleProductId, AuthenticatedUser user) {
        SaleProduct saleProduct = require(saleProductId, user);
        brandWriteGuard.requireWritable(saleProduct.getBrandId());
        return saleProduct;
    }

    private SaleProduct require(Long saleProductId, AuthenticatedUser user) {
        SaleProduct saleProduct = saleProductRepository.findById(saleProductId)
                .orElseThrow(() -> new NotFoundException("판매상품을 찾을 수 없습니다. saleProductId=" + saleProductId));
        if (!scopePolicy.canAccessBrand(saleProduct.getBrandId(), user)) {
            throw new ForbiddenException("해당 판매상품에 대한 권한이 없습니다. saleProductId=" + saleProductId);
        }
        return saleProduct;
    }
}

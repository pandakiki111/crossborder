package com.crossborder.oms.service.gift;

import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftEvent;
import com.crossborder.common.entity.gift.GiftEventActivePeriod;
import com.crossborder.common.entity.gift.GiftEventCondition;
import com.crossborder.common.entity.gift.GiftEventItem;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.infra.jpa.AuditorContext;
import com.crossborder.oms.dto.gift.GiftEventRequest;
import com.crossborder.oms.dto.gift.GiftEventResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.GiftEventActivePeriodRepository;
import com.crossborder.oms.repository.GiftEventConditionRepository;
import com.crossborder.oms.repository.GiftEventItemRepository;
import com.crossborder.oms.repository.GiftEventRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopePolicy;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 사은품 이벤트 관리 (브랜드 스코프). 판정·증정은 GiftEventApplier.
 * <ul>
 *   <li>수정·삭제: 지급 이력(grants)이 있으면 전면 불가 (409 — 새 이벤트로 등록). 지급된 증정의 근거를 바꾸지 않기 위해서다</li>
 *   <li>중단·재시작: 지급 후에도 가능. 활성 구간 이력에 마감·신규로만 남긴다 (판정은 주문 기준 시각이 든 구간을 본다)</li>
 *   <li>상태는 저장하지 않고 파생한다 (GiftEvent.statusAt)</li>
 * </ul>
 */
@Service
@Transactional
public class GiftEventService {

    private static final String CODE_UK = "uk_gift_events_code";
    /** 31^8 공간이라 충돌 자체가 드물다 — 몇 번이면 충분하다 */
    private static final int CODE_ATTEMPTS = 5;
    private static final String INSERT_EVENT = """
            INSERT INTO gift_events (code, brand_id, name, time_basis, starts_at, ends_at, amount_min, amount_max,
                                     condition_mode, grant_type, quantity_mode, fixed_qty, per_qty_unit, per_qty_give,
                                     aggregation, created_user_id, updated_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final GiftEventRepository eventRepository;
    private final GiftEventConditionRepository conditionRepository;
    private final GiftEventItemRepository itemRepository;
    private final GiftEventActivePeriodRepository periodRepository;
    private final SaleProductRepository saleProductRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final ScopePolicy scopePolicy;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Long systemUserId;

    public GiftEventService(GiftEventRepository eventRepository, GiftEventConditionRepository conditionRepository,
                            GiftEventItemRepository itemRepository, GiftEventActivePeriodRepository periodRepository,
                            SaleProductRepository saleProductRepository, ProductRepository productRepository,
                            BrandRepository brandRepository, ScopePolicy scopePolicy, JdbcTemplate jdbcTemplate,
                            Clock clock, @Value("${crossborder.audit.system-user-id:1}") Long systemUserId) {
        this.eventRepository = eventRepository;
        this.conditionRepository = conditionRepository;
        this.itemRepository = itemRepository;
        this.periodRepository = periodRepository;
        this.saleProductRepository = saleProductRepository;
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.scopePolicy = scopePolicy;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.systemUserId = systemUserId;
    }

    /**
     * @throws NotFoundException       브랜드 없음
     * @throws ForbiddenException      스코프 밖 브랜드
     * @throws InvalidRequestException 정의·조건·품목 규칙 위반
     */
    public GiftEventResponse create(GiftEventRequest request, AuthenticatedUser user) {
        scopePolicy.requireBrand(request.brandId(), user);
        GiftEvent draft = definitionOf(() -> GiftEvent.create(request.brandId(), toDefinition(request)));
        GiftEvent event = eventRepository.findById(insertWithCode(draft)).orElseThrow();
        saveDetails(event, request);
        periodRepository.save(GiftEventActivePeriod.open(event.getId(), event.getStartsAt()));
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public GiftEventResponse get(Long eventId, AuthenticatedUser user) {
        return toResponse(require(eventId, user));
    }

    /** brandId 없으면 스코프 안 전체 (ADMIN 전체 / COMPANY_STAFF 자기 회사 브랜드 / BRAND_STAFF 자기 브랜드) */
    @Transactional(readOnly = true)
    public List<GiftEventResponse> list(Long brandId, AuthenticatedUser user) {
        List<GiftEvent> events;
        if (brandId != null) {
            scopePolicy.requireBrand(brandId, user);
            events = eventRepository.findByBrandIdInOrderByIdDesc(List.of(brandId));
        } else {
            events = switch (user.role()) {
                case ADMIN -> eventRepository.findAllByOrderByIdDesc();
                case COMPANY_STAFF -> eventRepository.findByBrandIdInOrderByIdDesc(
                        brandRepository.findByCompanyId(user.companyId()).stream().map(Brand::getId).toList());
                case BRAND_STAFF -> eventRepository.findByBrandIdInOrderByIdDesc(List.of(user.brandId()));
                case WORKER -> List.of();
            };
        }
        return events.stream().map(this::toResponse).toList();
    }

    /**
     * 정의 전체 교체. 활성 구간은 [새 시작, 열림)으로 다시 만들고, 중단 중이었으면 지금 시각으로 다시 마감한다
     * (새 시작이 지금 이후라 마감할 수 없으면 활성으로 둔다 — 시작 전 이벤트의 중단은 없다).
     *
     * @throws ConflictException 지급 이력 있음
     */
    public GiftEventResponse update(Long eventId, GiftEventRequest request, AuthenticatedUser user) {
        GiftEvent event = require(eventId, user);
        if (!event.getBrandId().equals(request.brandId())) {
            throw new InvalidRequestException("이벤트 브랜드는 바꿀 수 없습니다.");
        }
        requireNoGrants(event, "수정");
        boolean stopped = periodRepository.findByGiftEventIdAndActiveToIsNull(eventId).isEmpty();
        definitionOf(() -> {
            event.redefine(toDefinition(request));
            return event;
        });
        conditionRepository.deleteByGiftEventId(eventId);
        itemRepository.deleteByGiftEventId(eventId);
        periodRepository.deleteByGiftEventId(eventId);
        saveDetails(event, request);
        GiftEventActivePeriod period = periodRepository.save(GiftEventActivePeriod.open(eventId, event.getStartsAt()));
        LocalDateTime now = now();
        if (stopped && event.getStartsAt().isBefore(now)) {
            period.close(now);
        }
        return toResponse(eventRepository.save(event));
    }

    /**
     * @throws ConflictException 지급 이력 있음
     */
    public void delete(Long eventId, AuthenticatedUser user) {
        GiftEvent event = require(eventId, user);
        requireNoGrants(event, "삭제");
        conditionRepository.deleteByGiftEventId(eventId);
        itemRepository.deleteByGiftEventId(eventId);
        periodRepository.deleteByGiftEventId(eventId);
        eventRepository.delete(event);
    }

    /**
     * 중단 = 열린 활성 구간을 지금 시각으로 마감. 지급 후에도 가능.
     *
     * @throws ConflictException 이미 중단됨 / 종료됨 / 시작 전
     */
    public GiftEventResponse stop(Long eventId, AuthenticatedUser user) {
        GiftEvent event = require(eventId, user);
        LocalDateTime now = now();
        requireNotEnded(event, now, "중단");
        GiftEventActivePeriod open = periodRepository.findByGiftEventIdAndActiveToIsNull(eventId)
                .orElseThrow(() -> new ConflictException("이미 중단된 이벤트입니다."));
        if (!open.getActiveFrom().isBefore(now)) {
            throw new ConflictException("시작 전 이벤트는 중단할 수 없습니다. 수정하거나 삭제하세요.");
        }
        open.close(now);
        return toResponse(event);
    }

    /**
     * 재시작 = 지금 시각부터 새 활성 구간. 중단 구간의 주문은 재시작해도 대상이 아니다.
     *
     * @throws ConflictException 이미 활성 / 종료됨
     */
    public GiftEventResponse restart(Long eventId, AuthenticatedUser user) {
        GiftEvent event = require(eventId, user);
        LocalDateTime now = now();
        requireNotEnded(event, now, "재시작");
        if (periodRepository.findByGiftEventIdAndActiveToIsNull(eventId).isPresent()) {
            throw new ConflictException("이미 활성 중인 이벤트입니다.");
        }
        periodRepository.saveAndFlush(GiftEventActivePeriod.open(eventId, now));
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public String preview(Long eventId, AuthenticatedUser user) {
        return toResponse(require(eventId, user)).preview();
    }

    // ---------------------------------------------------------------- 내부

    private GiftEvent require(Long eventId, AuthenticatedUser user) {
        GiftEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("사은품 이벤트를 찾을 수 없습니다. eventId=" + eventId));
        if (!scopePolicy.canAccessBrand(event.getBrandId(), user)) {
            throw new ForbiddenException("해당 이벤트에 대한 권한이 없습니다. eventId=" + eventId);
        }
        return event;
    }

    private void requireNoGrants(GiftEvent event, String action) {
        if (grantCount(event.getId()) > 0) {
            throw new ConflictException("지급 이력이 있는 이벤트는 " + action + "할 수 없습니다. 새 이벤트로 등록하세요 (중단·재시작은 가능).");
        }
    }

    private static void requireNotEnded(GiftEvent event, LocalDateTime now, String action) {
        if (!now.isBefore(event.getEndsAt())) {
            throw new ConflictException("기간이 끝난 이벤트는 " + action + "할 수 없습니다.");
        }
    }

    /**
     * 이벤트 행을 서버 생성 코드로 넣는다. 코드 충돌(uk_gift_events_code)이면 새 코드로 재시도한다.
     * JDBC로 넣는 이유: JPA flush에서 유니크 위반이 나면 영속성 컨텍스트를 이어 쓸 수 없어 같은 트랜잭션에서 재시도할 수 없다
     * (MariaDB는 실패한 문장만 되돌리므로 JDBC INSERT는 트랜잭션을 유지한 채 다시 시도할 수 있다).
     */
    private Long insertWithCode(GiftEvent draft) {
        Long userId = AuditorContext.get().orElse(systemUserId);
        GiftEvent.Definition d = draft.definition();
        for (int attempt = 1; ; attempt++) {
            String code = GiftEventCodeGenerator.generate();
            KeyHolder key = new GeneratedKeyHolder();
            try {
                jdbcTemplate.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(INSERT_EVENT, Statement.RETURN_GENERATED_KEYS);
                    Object[] values = {code, draft.getBrandId(), d.name().trim(), d.timeBasis().name(), d.startsAt(),
                            d.endsAt(), d.amountMin(), d.amountMax(), d.conditionMode().name(), d.grantType().name(),
                            d.quantityMode().name(), d.fixedQty(), d.perQtyUnit(), d.perQtyGive(),
                            d.aggregation() == null ? null : d.aggregation().name(), userId, userId};
                    for (int i = 0; i < values.length; i++) {
                        ps.setObject(i + 1, values[i]);
                    }
                    return ps;
                }, key);
                return key.getKey().longValue();
            } catch (DuplicateKeyException e) {
                boolean codeCollision = NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains(CODE_UK);
                if (!codeCollision || attempt >= CODE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private long grantCount(Long eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gift_event_grants WHERE gift_event_id = ?",
                Long.class, eventId);
    }

    /** 조건·품목 저장. 판매상품코드·SKU·증정 제품은 이벤트 브랜드 것만 */
    private void saveDetails(GiftEvent event, GiftEventRequest request) {
        List<GiftEventRequest.Condition> conditions = request.conditions() == null ? List.of() : request.conditions();
        if (event.getQuantityMode() == GiftQuantityMode.PER_QUANTITY && conditions.isEmpty()) {
            throw new InvalidRequestException("PER_QUANTITY는 상품 조건이 필요합니다 (조건 상품 구매수량으로 계산).");
        }
        Long brandId = event.getBrandId();
        Set<String> codes = conditions.stream().filter(c -> c.targetType() == GiftConditionTarget.SALE_PRODUCT)
                .map(GiftEventRequest.Condition::saleProductCode).filter(c -> c != null).collect(Collectors.toSet());
        Set<String> knownCodes = codes.isEmpty() ? Set.of() : saleProductRepository.findByBrandIdAndCodeIn(brandId, codes)
                .stream().map(SaleProduct::getCode).collect(Collectors.toSet());
        Set<String> skus = conditions.stream().filter(c -> c.targetType() == GiftConditionTarget.SKU)
                .map(GiftEventRequest.Condition::sku).filter(s -> s != null).collect(Collectors.toSet());
        Set<String> knownSkus = skus.isEmpty() ? Set.of() : productRepository.findByBrandIdAndSkuIn(brandId, skus)
                .stream().map(Product::getSku).collect(Collectors.toSet());

        List<GiftEventCondition> conditionRows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GiftEventRequest.Condition c : conditions) {
            GiftEventCondition row = definitionOf(() -> toCondition(event.getId(), c));
            if (row.getTargetType() == GiftConditionTarget.SKU ? !knownSkus.contains(row.getSku())
                    : !knownCodes.contains(row.getSaleProductCode())) {
                throw new InvalidRequestException("이벤트 브랜드에 없는 조건 상품입니다: "
                        + (row.getSku() != null ? row.getSku() : row.getSaleProductCode()));
            }
            if (!seen.add(row.getTargetType() + "|" + row.getSaleProductCode() + "|" + row.getOptionCode() + "|" + row.getSku())) {
                throw new InvalidRequestException("같은 조건이 두 번 들어 있습니다.");
            }
            conditionRows.add(row);
        }

        List<GiftEventRequest.Item> items = request.items();
        Map<Long, Product> products = productRepository.findAllById(items.stream().map(GiftEventRequest.Item::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<GiftEventItem> itemRows = new ArrayList<>();
        Set<Long> productIds = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            GiftEventRequest.Item item = items.get(i);
            Product product = products.get(item.productId());
            if (product == null || !product.getBrandId().equals(brandId)) {
                throw new InvalidRequestException("이벤트 브랜드의 제품이 아닙니다. productId=" + item.productId());
            }
            if (!productIds.add(item.productId())) {
                throw new InvalidRequestException("같은 증정 제품이 두 번 들어 있습니다. productId=" + item.productId());
            }
            int priority = item.priority() != null ? item.priority() : i + 1;
            if (event.getGrantType() == GiftGrantType.SEQUENTIAL && !priorities.add(priority)) {
                throw new InvalidRequestException("SEQUENTIAL 품목의 우선순위가 겹칩니다. priority=" + priority);
            }
            itemRows.add(definitionOf(() -> GiftEventItem.create(event.getId(), event.getGrantType(), item.productId(),
                    priority, item.limitQty())));
        }
        conditionRepository.saveAll(conditionRows);
        itemRepository.saveAll(itemRows);
    }

    private GiftEventResponse toResponse(GiftEvent event) {
        List<GiftEventCondition> conditions = conditionRepository.findByGiftEventIdInOrderByIdAsc(List.of(event.getId()));
        List<GiftEventItem> items = itemRepository.findByGiftEventIdInOrderByPriorityAscIdAsc(List.of(event.getId()));
        List<GiftEventActivePeriod> periods = periodRepository.findByGiftEventIdInOrderByActiveFromAsc(List.of(event.getId()));
        Map<Long, String> skus = productRepository.findAllById(items.stream().map(GiftEventItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getSku));
        String preview = GiftEventPreview.describe(event, conditions, items.stream()
                .map(i -> new GiftEventPreview.Gift(skus.get(i.getProductId()), i.getLimitQty())).toList());
        boolean open = periods.stream().anyMatch(GiftEventActivePeriod::isOpen);
        return new GiftEventResponse(event.getId(), event.getCode(), event.getBrandId(), event.getName(), event.getTimeBasis(),
                event.getStartsAt(), event.getEndsAt(), event.getAmountMin(), event.getAmountMax(),
                event.getConditionMode(), event.getGrantType(), event.getQuantityMode(), event.getFixedQty(),
                event.getPerQtyUnit(), event.getPerQtyGive(), event.getAggregation(), event.statusAt(now(), open),
                grantCount(event.getId()),
                conditions.stream().map(c -> new GiftEventResponse.Condition(c.getTargetType(), c.getSaleProductCode(),
                        c.getOptionCode(), c.getSku())).toList(),
                items.stream().map(i -> new GiftEventResponse.Item(i.getId(), i.getProductId(), skus.get(i.getProductId()),
                        i.getPriority(), i.getLimitQty(), i.getGrantedQty(),
                        i.getLimitQty() == null ? null : i.getLimitQty() - i.getGrantedQty())).toList(),
                periods.stream().map(p -> new GiftEventResponse.Period(p.getActiveFrom(), p.getActiveTo())).toList(),
                preview);
    }

    private static GiftEvent.Definition toDefinition(GiftEventRequest r) {
        return new GiftEvent.Definition(r.name(), r.timeBasis(), r.startsAt(), r.endsAt(), r.amountMin(), r.amountMax(),
                r.conditionMode(), r.grantType(), r.quantityMode(), r.fixedQty(), r.perQtyUnit(), r.perQtyGive(),
                r.aggregation());
    }

    /** 엔티티 규칙 위반(IllegalArgumentException)을 400으로 */
    private static <T> T definitionOf(Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static GiftEventCondition toCondition(Long eventId, GiftEventRequest.Condition c) {
        if (c.targetType() == GiftConditionTarget.SKU) {
            if (StringUtils.hasText(c.saleProductCode()) || c.optionCode() != null) {
                throw new IllegalArgumentException("SKU 조건에는 판매상품코드·옵션을 넣지 않습니다.");
            }
            return GiftEventCondition.ofSku(eventId, c.sku());
        }
        if (StringUtils.hasText(c.sku())) {
            throw new IllegalArgumentException("판매상품 조건에는 SKU를 넣지 않습니다.");
        }
        return GiftEventCondition.ofSaleProduct(eventId, c.saleProductCode(), c.optionCode());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}

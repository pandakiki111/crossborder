package com.crossborder.oms.service.product;

import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.common.entity.product.SaleProductChannelMapping;
import com.crossborder.common.entity.product.SalesChannel;
import com.crossborder.oms.dto.product.ChannelMappingChangeResponse;
import com.crossborder.oms.dto.product.ChannelMappingRequest;
import com.crossborder.oms.dto.product.ChannelMappingResponse;
import com.crossborder.oms.dto.product.ChannelMappingSearchCondition;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.SaleProductChannelMappingRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import com.crossborder.oms.repository.SalesChannelRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.service.order.OrderMappingService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매상품-채널 상품코드 매핑 관리. 매핑은 이력이다.
 * <p>
 * 키 = (채널, 브랜드, 코드, 옵션코드). 브랜드는 판매상품에서 정해진다.
 * <ul>
 *   <li>등록: 키에 이력이 없으면 기간 시작 없음(과거 주문까지)부터, 마감된 이력만 있으면
 *       max(처리 시각, 최종 마감 시각)부터 유효 — 서버 간 시계가 어긋나도 이전 행과 기간이 겹치지 않는다.
 *       등록 직후 그 키의 매핑안됨 주문 항목을 주문 시각 기준으로 확정한다</li>
 *   <li>재지정: 현재 행을 처리 시각에 마감하고 같은 시각부터 유효한 신규 행 추가. 과거로 소급하지 않으므로
 *       이미 수집된 주문은 그대로 맞고, 늦게 수집되는 주문도 주문 시각으로 구/신 매핑이 갈린다</li>
 *   <li>삭제: 처리 시각에 마감. 행은 남는다 (마감 전 주문의 재수집에 필요)</li>
 * </ul>
 * 처리 시각은 서버 시각이고, 마켓 주문 시각(ordered_at)과 같은 시간대(UTC+9)라는 전제다.
 */
@Service
public class ChannelMappingService {

    private final SaleProductChannelMappingRepository mappingRepository;
    private final SaleProductRepository saleProductRepository;
    private final SalesChannelRepository salesChannelRepository;
    private final ScopePolicy scopePolicy;
    private final OrderMappingService orderMappingService;
    private final Clock clock;

    public ChannelMappingService(SaleProductChannelMappingRepository mappingRepository,
                                 SaleProductRepository saleProductRepository,
                                 SalesChannelRepository salesChannelRepository, ScopePolicy scopePolicy,
                                 OrderMappingService orderMappingService, Clock clock) {
        this.mappingRepository = mappingRepository;
        this.saleProductRepository = saleProductRepository;
        this.salesChannelRepository = salesChannelRepository;
        this.scopePolicy = scopePolicy;
        this.orderMappingService = orderMappingService;
        this.clock = clock;
    }

    /**
     * TODO: QueryDSL 목록 (기본은 현재 유효 행, 이력 포함 옵션)
     */
    @Transactional(readOnly = true)
    public Page<ChannelMappingResponse> search(ChannelMappingSearchCondition condition, Pageable pageable,
                                               AuthenticatedUser user) {
        throw new UnsupportedOperationException("상품 매핑 목록 미구현");
    }

    @Transactional
    public ChannelMappingChangeResponse create(ChannelMappingRequest request, AuthenticatedUser user) {
        SaleProduct saleProduct = loadSaleProduct(request.saleProductId(), user);
        SalesChannel channel = loadChannel(request.channelId());
        return open(saleProduct, channel.getId(), request.code().trim(),
                SaleProductChannelMapping.normalizeOptionCode(trim(request.optionCode())), now());
    }

    /**
     * 판매상품만 바뀌면 재지정(마감 + 신규), 코드·옵션이 바뀌면 기존 키 마감 + 새 키 등록.
     */
    @Transactional
    public ChannelMappingChangeResponse update(Long mappingId, ChannelMappingRequest request,
                                               AuthenticatedUser user) {
        SaleProductChannelMapping current = loadCurrentForUpdate(mappingId, user);
        if (!current.getChannelId().equals(request.channelId())) {
            throw new InvalidRequestException("채널은 변경할 수 없습니다. 다른 채널은 새 매핑으로 등록하세요.");
        }
        SaleProduct saleProduct = loadSaleProduct(request.saleProductId(), user);
        if (!current.getBrandId().equals(saleProduct.getBrandId())) {
            throw new InvalidRequestException("다른 브랜드 판매상품으로 재지정할 수 없습니다.");
        }
        String code = request.code().trim();
        String optionCode = SaleProductChannelMapping.normalizeOptionCode(trim(request.optionCode()));
        LocalDateTime now = now();

        boolean sameKey = code.equals(current.getCode()) && optionCode.equals(current.getOptionCode());
        if (sameKey) {
            if (current.getSaleProductId().equals(saleProduct.getId())) {
                return new ChannelMappingChangeResponse(current.getId(), 0, 0);
            }
            SaleProductChannelMapping next = current.renew(saleProduct, now);
            // 마감(UPDATE)을 신규(INSERT)보다 먼저 반영 — Hibernate는 INSERT를 먼저 내보내 현재 행 유니크에 걸린다
            mappingRepository.flush();
            return new ChannelMappingChangeResponse(mappingRepository.save(next).getId(), 0, 0);
        }

        current.close(now);
        mappingRepository.flush();
        return open(saleProduct, current.getChannelId(), code, optionCode, now);
    }

    /**
     * 처리 시각에 마감. 마감 이후 주문은 매핑안됨으로 들어온다.
     */
    @Transactional
    public void delete(Long mappingId, AuthenticatedUser user) {
        loadCurrentForUpdate(mappingId, user).close(now());
    }

    private ChannelMappingChangeResponse open(SaleProduct saleProduct, Long channelId, String code,
                                              String optionCode, LocalDateTime now) {
        Long brandId = saleProduct.getBrandId();
        if (mappingRepository.findCurrentForUpdate(channelId, brandId, code, optionCode).isPresent()) {
            throw new ConflictException("이미 유효한 매핑이 있습니다. 판매상품을 바꾸려면 재지정하세요. code=" + code
                    + ", optionCode=" + optionCode);
        }
        // 현재 행이 없으므로 이력이 있다면 전부 마감 행이다. 재개는 최종 마감 이후부터 — 마감 시각이 처리 시각보다
        // 늦으면(다른 서버가 앞선 시계로 마감) 처리 시각으로 열면 두 행의 기간이 겹친다
        LocalDateTime effectiveFrom = mappingRepository.findLastEffectiveTo(channelId, brandId, code, optionCode)
                .map(lastEffectiveTo -> lastEffectiveTo.isAfter(now) ? lastEffectiveTo : now)
                .orElse(SaleProductChannelMapping.UNBOUNDED_PAST);
        SaleProductChannelMapping mapping = mappingRepository.save(
                SaleProductChannelMapping.create(saleProduct, channelId, code, optionCode, effectiveFrom));
        OrderMappingService.Result resolved =
                orderMappingService.resolveUnmappedItems(channelId, brandId, code, optionCode);
        return new ChannelMappingChangeResponse(mapping.getId(), resolved.mappedItemCount(),
                resolved.completedOrderCount());
    }

    private SaleProductChannelMapping loadCurrentForUpdate(Long mappingId, AuthenticatedUser user) {
        SaleProductChannelMapping mapping = mappingRepository.findByIdForUpdate(mappingId)
                .orElseThrow(() -> new NotFoundException("매핑을 찾을 수 없습니다. mappingId=" + mappingId));
        if (!scopePolicy.canAccessBrand(mapping.getBrandId(), user)) {
            throw new ForbiddenException("해당 매핑에 대한 권한이 없습니다. mappingId=" + mappingId);
        }
        if (!mapping.isCurrent()) {
            throw new ConflictException("마감된 매핑(이력)은 변경할 수 없습니다. mappingId=" + mappingId);
        }
        return mapping;
    }

    private SaleProduct loadSaleProduct(Long saleProductId, AuthenticatedUser user) {
        SaleProduct saleProduct = saleProductRepository.findById(saleProductId)
                .orElseThrow(() -> new InvalidRequestException("판매상품을 찾을 수 없습니다. saleProductId=" + saleProductId));
        if (!scopePolicy.canAccessBrand(saleProduct.getBrandId(), user)) {
            throw new ForbiddenException("해당 판매상품에 대한 권한이 없습니다. saleProductId=" + saleProductId);
        }
        if (!saleProduct.isActive()) {
            throw new InvalidRequestException("비활성 판매상품입니다. saleProductId=" + saleProductId);
        }
        return saleProduct;
    }

    private SalesChannel loadChannel(Long channelId) {
        SalesChannel channel = salesChannelRepository.findById(channelId)
                .orElseThrow(() -> new InvalidRequestException("채널이 없습니다. channelId=" + channelId));
        if (!channel.isActive()) {
            throw new InvalidRequestException("비활성 채널입니다. channelId=" + channelId);
        }
        return channel;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}

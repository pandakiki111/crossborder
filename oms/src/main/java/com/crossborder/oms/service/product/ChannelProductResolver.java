package com.crossborder.oms.service.product;

import com.crossborder.common.entity.product.SaleProductChannelMapping;
import com.crossborder.oms.repository.SaleProductChannelMappingRepository;
import com.crossborder.oms.service.support.InClause;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * (채널, 브랜드, 채널상품코드, 옵션코드, 주문 시각) → 판매상품 확정. 주문 수집의 관문이라 수집 API·엑셀 시딩이 공유한다.
 * <p>
 * 매핑은 이력이라 수집 시각이 아니라 주문 시각(ordered_at)이 속한 기간의 매핑을 쓴다.
 * 배치 지연·재수집으로 늦게 들어온 주문도 주문 당시의 판매상품(구성)에 붙는다.
 * <p>
 * 채널 상품코드는 브랜드 간에 겹칠 수 있으므로 반드시 브랜드 안에서만 찾는다.
 * 옵션코드 null·공백은 매핑 테이블 규칙대로 ''(옵션 없음)로 정규화해서 조회한다.
 */
@Component
public class ChannelProductResolver {

    /**
     * @param optionCode 정규화된 값 (of()로 만들 것)
     */
    public record MappingKey(Long channelId, String code, String optionCode) {

        public static MappingKey of(Long channelId, String code, String optionCode) {
            return new MappingKey(channelId, code, SaleProductChannelMapping.normalizeOptionCode(optionCode));
        }
    }

    /**
     * 키별 매핑 이력. 주문 시각으로 그 시점의 판매상품을 찾는다.
     */
    public static final class MappingHistory {

        private final Map<MappingKey, List<SaleProductChannelMapping>> byKey;

        private MappingHistory(Map<MappingKey, List<SaleProductChannelMapping>> byKey) {
            this.byKey = byKey;
        }

        public Optional<Long> saleProductIdAt(MappingKey key, LocalDateTime orderedAt) {
            return byKey.getOrDefault(key, List.of()).stream()
                    .filter(m -> m.covers(orderedAt))
                    .map(SaleProductChannelMapping::getSaleProductId)
                    .findFirst();
        }
    }

    private final SaleProductChannelMappingRepository mappingRepository;

    public ChannelProductResolver(SaleProductChannelMappingRepository mappingRepository) {
        this.mappingRepository = mappingRepository;
    }

    /**
     * 대량 조회. 채널별로 상품코드를 IN 조회해 이력 전체를 가져오고, 옵션코드·기간은 메모리에서 맞춘다
     * (행마다 조회하지 않는다).
     */
    @Transactional(readOnly = true)
    public MappingHistory loadHistory(Long brandId, Collection<MappingKey> keys) {
        Set<MappingKey> wanted = Set.copyOf(keys);
        Map<MappingKey, List<SaleProductChannelMapping>> byKey = new HashMap<>();
        Map<Long, Set<String>> codesByChannel = wanted.stream().collect(Collectors.groupingBy(
                MappingKey::channelId, Collectors.mapping(MappingKey::code, Collectors.toSet())));
        codesByChannel.forEach((channelId, codes) -> {
            for (List<String> chunk : InClause.partition(codes)) {
                for (SaleProductChannelMapping m : mappingRepository.findHistoryByCodes(
                        brandId, channelId, chunk)) {
                    MappingKey key = new MappingKey(channelId, m.getCode(), m.getOptionCode());
                    if (wanted.contains(key)) {
                        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
                    }
                }
            }
        });
        return new MappingHistory(byKey);
    }
}

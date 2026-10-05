package com.crossborder.oms.service.order;

import com.crossborder.common.entity.product.SalesChannel;
import com.crossborder.oms.repository.SalesChannelRepository;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 판매채널 ID → 코드 메모리 캐시. 주문 목록이 채널을 조인하지 않고 코드를 붙이는 데 쓴다.
 * <p>
 * 채널은 몇 행뿐인 마스터이고 코드는 바뀌지 않는다 (SalesChannel에 코드 변경 없음).
 * 그래서 무효화 없이, 모르는 ID가 오면(새 채널) 전체를 다시 읽는다.
 */
@Component
public class SalesChannelCodes {

    private final SalesChannelRepository salesChannelRepository;
    private volatile Map<Long, String> codes = Map.of();

    public SalesChannelCodes(SalesChannelRepository salesChannelRepository) {
        this.salesChannelRepository = salesChannelRepository;
    }

    public String codeOf(Long channelId) {
        String code = codes.get(channelId);
        if (code == null) {
            reload();
            code = codes.get(channelId);
        }
        return code;
    }

    private void reload() {
        codes = salesChannelRepository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(SalesChannel::getId, SalesChannel::getCode));
    }
}

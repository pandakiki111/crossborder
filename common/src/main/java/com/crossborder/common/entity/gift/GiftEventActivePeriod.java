package com.crossborder.common.entity.gift;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이벤트 활성 구간 [activeFrom, activeTo). 매핑 이력(SaleProductChannelMapping)과 같은 규칙:
 * 중단 = 열린 행 마감, 재시작 = 새 행. 시작 시각을 고치거나 마감을 되돌리지 않는다.
 * 이벤트 등록 시 [startsAt, 열림) 1행. 열린 행은 이벤트당 최대 1개 (uk current_key).
 */
@Getter
@Entity
@Table(name = "gift_event_active_periods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftEventActivePeriod extends BaseAuditEntity {

    @Column(name = "gift_event_id", nullable = false, updatable = false)
    private Long giftEventId;

    @Column(name = "active_from", nullable = false, updatable = false)
    private LocalDateTime activeFrom;

    @Column(name = "active_to")
    private LocalDateTime activeTo;

    private GiftEventActivePeriod(Long giftEventId, LocalDateTime activeFrom) {
        this.giftEventId = Objects.requireNonNull(giftEventId);
        this.activeFrom = Objects.requireNonNull(activeFrom);
    }

    public static GiftEventActivePeriod open(Long giftEventId, LocalDateTime from) {
        return new GiftEventActivePeriod(giftEventId, from);
    }

    /**
     * 마감. 시작 이전 시각으로는 마감할 수 없다 (빈 구간이 된다).
     */
    public void close(LocalDateTime at) {
        if (activeTo != null) {
            throw new IllegalStateException("이미 마감된 활성 구간입니다. id=" + getId());
        }
        if (!activeFrom.isBefore(at)) {
            throw new IllegalStateException("활성 시작(" + activeFrom + ") 이후 시각에만 중단할 수 있습니다. at=" + at);
        }
        this.activeTo = at;
    }

    public boolean isOpen() {
        return activeTo == null;
    }

    public boolean contains(LocalDateTime t) {
        return !t.isBefore(activeFrom) && (activeTo == null || t.isBefore(activeTo));
    }
}

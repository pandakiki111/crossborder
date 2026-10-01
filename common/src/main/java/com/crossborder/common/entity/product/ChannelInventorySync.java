package com.crossborder.common.entity.product;

import com.crossborder.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널별 재고 전송 상태 (채널×제품당 1행 유지)
 */
@Getter
@Entity
@Table(name = "channel_inventory_sync")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelInventorySync extends BaseEntity {

    @Column(name = "channel_id", nullable = false, updatable = false)
    private Long channelId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** 채널에 마지막으로 전송한 판매가능재고 (전송 전이면 null) */
    @Column(name = "last_sent_quantity")
    private Integer lastSentQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    private SyncStatus syncStatus;

    @Column(name = "fail_reason", length = 300)
    private String failReason;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    private ChannelInventorySync(Long channelId, Long productId) {
        this.channelId = channelId;
        this.productId = productId;
        this.syncStatus = SyncStatus.PENDING;
    }

    public static ChannelInventorySync create(Long channelId, Long productId) {
        return new ChannelInventorySync(channelId, productId);
    }

    /**
     * 재고 변동으로 재전송이 필요해짐
     */
    public void requestSync() {
        this.syncStatus = SyncStatus.PENDING;
    }

    /**
     * 전송 성공 기록.
     * <p>
     * 주의(경합): 전송 중(API 호출 수 초)에 재고가 바뀌어 requestSync()로 PENDING이 찍혀도,
     * 뒤늦게 돌아온 이전 전송의 성공이 이 메서드로 SUCCESS로 덮어써 재전송 트리거가 유실된다.
     * 예) 100 전송 시작 → 그 사이 할당 +5로 PENDING → 100 전송 성공으로 SUCCESS
     *     → 채널엔 100, 실제는 95인데 다음 재고 변동까지 방치 (오버셀 틈)
     * 배치/동기화 구현 시 "보낸 수량 기준으로 그 사이 변동이 없을 때만 SUCCESS" 같은
     * 조건부 갱신으로 막아야 한다. 이 메서드 단독으로는 막을 수 없음.
     */
    public void markSuccess(int sentQuantity, LocalDateTime syncedAt) {
        this.syncStatus = SyncStatus.SUCCESS;
        this.lastSentQuantity = sentQuantity;
        this.failReason = null;
        this.lastSyncedAt = syncedAt;
    }

    public void markFailed(String failReason, LocalDateTime syncedAt) {
        this.syncStatus = SyncStatus.FAILED;
        this.failReason = failReason;
        this.lastSyncedAt = syncedAt;
    }

    public boolean needsSync() {
        return syncStatus != SyncStatus.SUCCESS;
    }
}

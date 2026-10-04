package com.crossborder.oms.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jpa.EntityScanConfig;
import com.crossborder.infra.jpa.JpaAuditingConfig;
import com.crossborder.infra.jpa.QuerydslConfig;
import com.crossborder.oms.dto.product.ChannelMappingRequest;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.service.order.OrderMappingService;
import com.crossborder.oms.service.product.ChannelProductResolver.MappingHistory;
import com.crossborder.oms.service.product.ChannelProductResolver.MappingKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 채널 매핑 이력: 재지정 원자성, 경계 시각 매칭, 동시 재지정, 같은 초 재지정, 재개 시 기간 겹침 방지.
 * <p>
 * 동시성은 실제 커밋·행 잠금이 필요해 테스트 트랜잭션을 끄고(NOT_SUPPORTED) 서비스 트랜잭션으로만 돌린다.
 * 데이터는 테스트마다 만들고 지운다. 처리 시각은 가변 시계로 고정한다.
 */
@DataJpaTest(properties = "DB_PASSWORD=unused")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({EntityScanConfig.class, JpaAuditingConfig.class, QuerydslConfig.class})
@Import({ChannelMappingService.class, OrderMappingService.class, ChannelProductResolver.class, ScopePolicy.class,
        ChannelMappingHistoryTest.ClockTestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ChannelMappingHistoryTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);
    private static final String CODE = "Q-TEST";

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 10, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 11, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock();
        }
    }

    @Autowired
    private ChannelMappingService channelMappingService;
    @Autowired
    private ChannelProductResolver channelProductResolver;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private long companyId;
    private long brandId;
    private long channelId;
    private long saleProduct1;
    private long saleProduct2;

    @BeforeEach
    void setUp() {
        companyId = insert("INSERT INTO companies (name) VALUES ('매핑테스트상사')");
        brandId = insert("INSERT INTO brands (company_id, name) VALUES (?, '매핑테스트브랜드')", companyId);
        saleProduct1 = insert("INSERT INTO sale_products (brand_id, name, code, created_user_id) VALUES (?, '구 구성', 'SP-OLD', 1)",
                brandId);
        saleProduct2 = insert("INSERT INTO sale_products (brand_id, name, code, created_user_id) VALUES (?, '신 구성', 'SP-NEW', 1)",
                brandId);
        channelId = jdbc.queryForObject("SELECT id FROM sales_channels WHERE code = 'QOO10'", Long.class);
        clock.set(T0);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM sale_product_channel_mappings WHERE brand_id = ?", brandId);
        jdbc.update("DELETE FROM sale_products WHERE brand_id = ?", brandId);
        jdbc.update("DELETE FROM brands WHERE id = ?", brandId);
        jdbc.update("DELETE FROM companies WHERE id = ?", companyId);
    }

    @Test
    void 재지정은_구_행_마감과_신_행_추가가_함께_반영된다() {
        long m1 = create(saleProduct1);

        clock.set(T1);
        long m2 = channelMappingService.update(m1, request(saleProduct2), ADMIN).mappingId();

        assertThat(effectiveTo(m1)).isEqualTo(T1);
        assertThat(effectiveFrom(m2)).isEqualTo(T1);
        assertThat(effectiveTo(m2)).isNull();
        assertThat(currentRowCount()).isEqualTo(1);
    }

    @Test
    void 경계_시각_주문은_정확히_한_행에만_매칭된다() {
        long m1 = create(saleProduct1);
        clock.set(T1);
        channelMappingService.update(m1, request(saleProduct2), ADMIN);

        MappingKey key = MappingKey.of(channelId, CODE, null);
        MappingHistory history = channelProductResolver.loadHistory(brandId, List.of(key));

        assertThat(history.saleProductIdAt(key, T1.minusSeconds(1))).contains(saleProduct1);
        assertThat(history.saleProductIdAt(key, T1)).contains(saleProduct2);
        assertThat(history.saleProductIdAt(key, T1.plusSeconds(1))).contains(saleProduct2);
        for (LocalDateTime at : List.of(T1.minusSeconds(1), T1, T1.plusSeconds(1))) {
            assertThat(coveringRowCount(at)).as("at=%s", at).isEqualTo(1);
        }
    }

    @Test
    void 동시_재지정은_한_건만_성공하고_현재_행은_하나로_유지된다() throws Exception {
        long m1 = create(saleProduct1);
        clock.set(T1);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return channelMappingService.update(m1, request(saleProduct2), ADMIN).mappingId();
            }));
        }
        start.countDown();

        int success = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<Long> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        executor.shutdown();

        assertThat(success).isEqualTo(1);
        // 409로 응답되는 예외만 허용 (잠금 대기 후 마감된 행을 보거나, 유니크가 최종 방어)
        assertThat(failures).hasSize(threads - 1)
                .allSatisfy(e -> assertThat(e).isInstanceOfAny(ConflictException.class,
                        DataIntegrityViolationException.class));
        assertThat(currentRowCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void 같은_초에_두_번_재지정하면_두_번째는_거부되고_이력은_그대로다() {
        long m1 = create(saleProduct1);
        clock.set(T1);
        long m2 = channelMappingService.update(m1, request(saleProduct2), ADMIN).mappingId();

        // 같은 초: m2를 T1에 마감하면 [T1, T1) 빈 구간이 된다 → Shipment 전이 위반과 같은 409(IllegalStateException)
        assertThatThrownBy(() -> channelMappingService.update(m2, request(saleProduct1), ADMIN))
                .isInstanceOf(IllegalStateException.class);

        assertThat(effectiveTo(m2)).isNull();
        assertThat(currentRowCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void 재개_시작시각은_처리시각과_최종_마감시각_중_늦은_쪽이다() {
        long m1 = create(saleProduct1);
        clock.set(T2);
        channelMappingService.delete(m1, ADMIN);

        // 시계가 늦은 서버에서 재개: 처리 시각(T1)이 최종 마감(T2)보다 이르다
        clock.set(T1);
        long reopened = create(saleProduct2);

        assertThat(effectiveFrom(reopened)).isEqualTo(T2);
        for (LocalDateTime at : List.of(T1, T2.minusSeconds(1), T2, T2.plusSeconds(1))) {
            assertThat(coveringRowCount(at)).as("at=%s", at).isEqualTo(1);
        }

        // 정상 시계: 처리 시각이 최종 마감보다 늦으면 처리 시각부터
        clock.set(T2.plusHours(1));
        channelMappingService.delete(reopened, ADMIN);
        clock.set(T2.plusHours(2));
        long reopenedAgain = create(saleProduct1);
        assertThat(effectiveFrom(reopenedAgain)).isEqualTo(T2.plusHours(2));
    }

    private long create(long saleProductId) {
        return channelMappingService.create(request(saleProductId), ADMIN).mappingId();
    }

    private ChannelMappingRequest request(long saleProductId) {
        return new ChannelMappingRequest(channelId, CODE, null, saleProductId);
    }

    private LocalDateTime effectiveFrom(long mappingId) {
        return jdbc.queryForObject("SELECT effective_from FROM sale_product_channel_mappings WHERE id = ?",
                LocalDateTime.class, mappingId);
    }

    private LocalDateTime effectiveTo(long mappingId) {
        return jdbc.queryForObject("SELECT effective_to FROM sale_product_channel_mappings WHERE id = ?",
                LocalDateTime.class, mappingId);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sale_product_channel_mappings WHERE brand_id = ?",
                Integer.class, brandId);
    }

    private int currentRowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_product_channel_mappings WHERE brand_id = ? AND effective_to IS NULL",
                Integer.class, brandId);
    }

    /** 반열림 구간 [from, to)로 at을 포함하는 행 수 */
    private int coveringRowCount(LocalDateTime at) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sale_product_channel_mappings
                WHERE brand_id = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                """, Integer.class, brandId, at, at);
    }

    private long insert(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 테스트에서 처리 시각을 옮기는 시계 */
    static class MutableClock extends Clock {

        private volatile Instant instant = Instant.now();

        void set(LocalDateTime at) {
            instant = at.atZone(ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

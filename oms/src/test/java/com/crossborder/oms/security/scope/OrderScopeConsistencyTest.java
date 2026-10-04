package com.crossborder.oms.security.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jpa.EntityScanConfig;
import com.crossborder.infra.jpa.JpaAuditingConfig;
import com.crossborder.infra.jpa.QuerydslConfig;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 주문 단건 스코프(ScopePolicy.requireOrder)와 목록 스코프(OrderQueryRepository.scope)는 같은 규칙을
 * 두 곳에 구현한다 (엔티티 판정 / QueryDSL 조건). 모든 role × 주문 조합에서 "단건 접근 가능 = 목록에 보임"을 검증한다.
 * <p>
 * 멀티브랜드 주문(한 주문에 A1·A2 항목)을 포함한다 — BRAND_STAFF 판정이 항목 브랜드 기준인지 확인하기 위해.
 */
@DataJpaTest(properties = "DB_PASSWORD=unused")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({EntityScanConfig.class, JpaAuditingConfig.class, QuerydslConfig.class})
@Import({OrderQueryRepository.class, ScopePolicy.class})
@Testcontainers
class OrderScopeConsistencyTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    private static final OrderSearchCondition NO_FILTER =
            new OrderSearchCondition(null, null, null, null, null, null, null, null);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScopePolicy scopePolicy;
    @Autowired
    private OrderQueryRepository orderQueryRepository;

    private long companyA;
    private long companyB;
    private long brandA1;
    private long brandA2;
    private long brandB1;
    private long orderA1;
    private long orderA1A2;
    private long orderB1;

    @BeforeEach
    void setUp() {
        companyA = insert("INSERT INTO companies (name) VALUES ('회사A')");
        companyB = insert("INSERT INTO companies (name) VALUES ('회사B')");
        brandA1 = insert("INSERT INTO brands (company_id, name) VALUES (?, '브랜드A1')", companyA);
        brandA2 = insert("INSERT INTO brands (company_id, name) VALUES (?, '브랜드A2')", companyA);
        brandB1 = insert("INSERT INTO brands (company_id, name) VALUES (?, '브랜드B1')", companyB);

        orderA1 = order("T-A1", companyA, brandA1);
        orderA1A2 = order("T-A1A2", companyA, brandA1, brandA2);
        orderB1 = order("T-B1", companyB, brandB1);
    }

    @Test
    void 모든_role에서_단건_접근과_목록_노출이_일치한다() {
        List<AuthenticatedUser> users = List.of(
                user(UserRole.ADMIN, null, null),
                user(UserRole.WORKER, null, null),
                user(UserRole.COMPANY_STAFF, companyA, null),
                user(UserRole.COMPANY_STAFF, companyB, null),
                user(UserRole.BRAND_STAFF, companyA, brandA1),
                user(UserRole.BRAND_STAFF, companyA, brandA2),
                user(UserRole.BRAND_STAFF, companyB, brandB1));

        for (AuthenticatedUser user : users) {
            assertThat(listed(user)).as("role=%s company=%s brand=%s", user.role(), user.companyId(), user.brandId())
                    .isEqualTo(accessible(user));
        }
    }

    /** 일치만 보면 양쪽이 같이 틀려도 통과하므로, 기대값도 명시한다 */
    @Test
    void 기대_스코프() {
        assertThat(accessible(user(UserRole.ADMIN, null, null))).containsExactlyInAnyOrder(orderA1, orderA1A2, orderB1);
        assertThat(accessible(user(UserRole.WORKER, null, null))).isEmpty();
        assertThat(accessible(user(UserRole.COMPANY_STAFF, companyA, null))).containsExactlyInAnyOrder(orderA1, orderA1A2);
        assertThat(accessible(user(UserRole.BRAND_STAFF, companyA, brandA1))).containsExactlyInAnyOrder(orderA1, orderA1A2);
        assertThat(accessible(user(UserRole.BRAND_STAFF, companyA, brandA2))).containsExactly(orderA1A2);
        assertThat(accessible(user(UserRole.BRAND_STAFF, companyB, brandB1))).containsExactly(orderB1);
    }

    private Set<Long> accessible(AuthenticatedUser user) {
        Set<Long> result = new HashSet<>();
        for (long orderId : List.of(orderA1, orderA1A2, orderB1)) {
            try {
                scopePolicy.requireOrder(orderId, user);
                result.add(orderId);
            } catch (ForbiddenException ignored) {
                // 스코프 밖
            }
        }
        return result;
    }

    private Set<Long> listed(AuthenticatedUser user) {
        Set<Long> testOrders = Set.of(orderA1, orderA1A2, orderB1);
        return orderQueryRepository.search(NO_FILTER, PageRequest.of(0, 100), user).getContent().stream()
                .map(OrderSummaryResponse::orderId)
                .filter(testOrders::contains)
                .collect(Collectors.toSet());
    }

    private long order(String channelOrderNo, long companyId, long... itemBrandIds) {
        long orderId = insert("""
                INSERT INTO orders (order_no, sales_channel_id, channel_order_no, company_id, orderer_name,
                                    receiver_name, receiver_address, ordered_at, created_user_id)
                SELECT ?, id, ?, ?, '주문자', '수취인', '주소', NOW(), 1 FROM sales_channels WHERE code = 'QOO10'
                """, "OMS-" + channelOrderNo, channelOrderNo, companyId);
        for (long brandId : itemBrandIds) {
            jdbc.update("""
                    INSERT INTO order_items (order_id, brand_id, item_type, channel_product_code, channel_option_code,
                                             quantity, created_user_id)
                    VALUES (?, ?, 'SALE_PRODUCT', 'CODE', '', 1, 1)
                    """, orderId, brandId);
        }
        return orderId;
    }

    private long insert(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static AuthenticatedUser user(UserRole role, Long companyId, Long brandId) {
        return new AuthenticatedUser(99L, role, companyId, brandId);
    }
}

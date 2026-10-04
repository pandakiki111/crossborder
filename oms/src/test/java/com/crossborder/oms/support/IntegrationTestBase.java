package com.crossborder.oms.support;

import com.crossborder.oms.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;

/**
 * 실제 MariaDB + Redis로 전체 컨텍스트를 띄우는 통합 테스트 공통 기반.
 * 컨테이너는 JVM당 한 번만 띄워(싱글턴) 하위 테스트 클래스가 Spring 컨텍스트 캐시를 공유한다.
 * 테스트 데이터는 SKU·이름 접미사로 테스트마다 분리한다 (정리하지 않음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "DB_PASSWORD=unused",
        "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "crossborder.lock.wait-time=2s"
})
public abstract class IntegrationTestBase {

    private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    static {
        MARIADB.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** @ScopeCheck 메서드 호출용 principal (현재 스레드) */
    protected static void login(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    protected long channelId(String code) {
        return jdbc.queryForObject("SELECT id FROM sales_channels WHERE code = ?", Long.class, code);
    }

    protected long company(String name) {
        return insert("INSERT INTO companies (name, business_no) VALUES (?, ?)", name + suffix, name + suffix);
    }

    protected long brand(long companyId, String name) {
        return insert("INSERT INTO brands (company_id, name) VALUES (?, ?)", companyId, name + suffix);
    }

    protected long product(long brandId, String name) {
        return insert("INSERT INTO products (brand_id, sku, name, name_eng, created_user_id) VALUES (?, ?, ?, ?, 1)",
                brandId, name + "-" + suffix, name, name);
    }

    protected long saleProduct(long brandId, String code) {
        return insert("INSERT INTO sale_products (brand_id, name, code, created_user_id) VALUES (?, ?, ?, 1)",
                brandId, code, code + "-" + suffix);
    }

    protected void composition(long saleProductId, long productId, int quantity, boolean gift) {
        jdbc.update("""
                INSERT INTO sale_product_items (sale_product_id, product_id, quantity, is_gift, created_user_id)
                VALUES (?, ?, ?, ?, 1)
                """, saleProductId, productId, quantity, gift);
    }

    protected int allocated(long productId) {
        return jdbc.queryForObject("SELECT allocated_stock FROM products WHERE id = ?", Integer.class, productId);
    }

    protected long insert(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}

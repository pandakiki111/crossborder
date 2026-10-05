package com.crossborder.oms.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V9 마이그레이션 검증. gift_source에는 DB CHECK가 없으므로(온라인 마이그레이션 비용으로 의도적 제외, V9 주석)
 * 기존 데이터 백필이 빠짐없이 됐는지를 여기서 확인한다: V8까지 올린 DB에 기존 사은품·구매 항목을 넣고 V9를 적용한다.
 */
@Testcontainers
class V9MigrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    /** 운영 점검 쿼리와 같다 (README §2) — 결과는 0이어야 한다 */
    static final String GIFT_WITHOUT_SOURCE =
            "SELECT COUNT(*) FROM order_items WHERE item_type = 'GIFT_PRODUCT' AND gift_source IS NULL";

    @Test
    void 기존_사은품_행은_COLLECTED로_백필되고_구매_항목은_NULL로_남는다() {
        // 한 연결로 고정 (LAST_INSERT_ID·세션 변수 foreign_key_checks 확인이 연결 단위)
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(mariadb.getJdbcUrl(),
                mariadb.getUsername(), mariadb.getPassword(), true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("8").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO companies (name) VALUES ('마이그레이션상사')");
        jdbc.update("INSERT INTO brands (company_id, name) VALUES (LAST_INSERT_ID(), '마이그레이션브랜드')");
        long brandId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO products (brand_id, sku, name, name_eng, created_user_id) VALUES (?, 'MIG-GIFT', 'g', 'g', 1)",
                brandId);
        long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO orders (order_no, sales_channel_id, channel_order_no, company_id, orderer_name, receiver_name,
                                    receiver_address, ordered_at, created_user_id)
                SELECT 'MIG-1', id, 'MIG-1', (SELECT company_id FROM brands WHERE id = ?), 'o', 'r', 'a', NOW(), 1
                FROM sales_channels WHERE code = 'QOO10'
                """, brandId);
        long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO order_items (order_id, brand_id, item_type, product_id, quantity, created_user_id)
                    VALUES (?, ?, 'GIFT_PRODUCT', ?, 1, 1)
                    """, orderId, brandId, productId);
        }
        jdbc.update("""
                INSERT INTO order_items (order_id, brand_id, item_type, channel_product_code, channel_option_code,
                                         quantity, created_user_id)
                VALUES (?, ?, 'SALE_PRODUCT', 'CODE', '', 1, 1)
                """, orderId, brandId);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject(GIFT_WITHOUT_SOURCE, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE gift_source = 'COLLECTED'", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE item_type = 'SALE_PRODUCT' AND gift_source IS NULL", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT @@foreign_key_checks", Integer.class)).as("V9가 FK 검사를 되돌려 놓음")
                .isEqualTo(1);
    }
}

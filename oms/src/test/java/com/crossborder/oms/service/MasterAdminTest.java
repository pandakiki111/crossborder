package com.crossborder.oms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.common.entity.organization.UserStatus;
import com.crossborder.oms.dto.gift.GiftEventRequest;
import com.crossborder.oms.dto.order.CancellationItemRequest;
import com.crossborder.oms.dto.organization.BrandCreateRequest;
import com.crossborder.oms.dto.organization.CompanyRequest;
import com.crossborder.oms.dto.organization.PasswordResetResponse;
import com.crossborder.oms.dto.organization.UserCreateRequest;
import com.crossborder.oms.dto.organization.UserResponse;
import com.crossborder.oms.dto.organization.UserUpdateRequest;
import com.crossborder.oms.dto.product.ChannelMappingRequest;
import com.crossborder.oms.dto.product.CompositionItemRequest;
import com.crossborder.oms.dto.product.ProductCreateRequest;
import com.crossborder.oms.dto.product.SaleProductCreateRequest;
import com.crossborder.oms.dto.product.SaleProductRenewRequest;
import com.crossborder.oms.dto.product.SaleProductRenewResponse;
import com.crossborder.oms.dto.product.SaleProductResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.gift.GiftEventService;
import com.crossborder.oms.service.order.OrderCancellationService;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.service.order.seed.OrderSeedService;
import com.crossborder.oms.service.organization.BrandAdminService;
import com.crossborder.oms.service.organization.CompanyAdminService;
import com.crossborder.oms.service.organization.UserAdminService;
import com.crossborder.oms.service.product.ChannelMappingService;
import com.crossborder.oms.service.product.ProductService;
import com.crossborder.oms.service.product.SaleProductService;
import com.crossborder.oms.support.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 마스터 관리: 연쇄 거부(회사·브랜드·제품·구성 변경), 계약종료 확인·재활성화 거부, 리뉴얼 원자성과 매핑 이력, 탈퇴 마스킹,
 * 비활성 브랜드 쓰기 409 / 조회·취소 허용, 소속 스코프 403.
 */
class MasterAdminTest extends IntegrationTestBase {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);

    @Autowired
    private CompanyAdminService companyService;
    @Autowired
    private BrandAdminService brandService;
    @Autowired
    private UserAdminService userService;
    @Autowired
    private ProductService productService;
    @Autowired
    private SaleProductService saleProductService;
    @Autowired
    private ChannelMappingService mappingService;
    @Autowired
    private GiftEventService giftEventService;
    @Autowired
    private OrderRegistrationService registrationService;
    @Autowired
    private OrderCancellationService cancellationService;
    @Autowired
    private OrderSeedService seedService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private long companyId;
    private long brandId;
    private long toner;
    private long qoo10;

    @BeforeEach
    void setUp() {
        login(ADMIN);
        qoo10 = channelId("QOO10");
        companyId = companyService.create(new CompanyRequest("마스터상사" + suffix, null)).id();
        brandId = brandService.create(new BrandCreateRequest(companyId, "마스터브랜드")).id();
        toner = productService.create(productRequest(brandId, "M-TONER-" + suffix), ADMIN).id();
    }

    // ------------------------------------------------------------------ 연쇄 거부 · 계약종료

    @Test
    void 회사는_ACTIVE_브랜드가_있으면_비활성화도_계약종료도_409() {
        assertThatThrownBy(() -> companyService.deactivate(companyId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("마스터브랜드");
        assertThatThrownBy(() -> companyService.terminate(companyId, "마스터상사" + suffix))
                .isInstanceOf(ConflictException.class);

        brandService.deactivate(brandId);
        assertThat(companyService.deactivate(companyId).status()).isEqualTo(ActiveStatus.INACTIVE);
    }

    @Test
    void 회사_계약종료는_계약종료되지_않은_비활성_브랜드가_있어도_409() {
        brandService.deactivate(brandId);
        assertThat(companyService.deactivate(companyId).status()).as("비활성화는 ACTIVE 브랜드만 본다")
                .isEqualTo(ActiveStatus.INACTIVE);
        assertThatThrownBy(() -> companyService.terminate(companyId, "마스터상사" + suffix))
                .isInstanceOf(ConflictException.class).hasMessageContaining("마스터브랜드").hasMessageContaining("INACTIVE");

        brandService.terminate(brandId, "마스터브랜드");
        assertThat(companyService.terminate(companyId, "마스터상사" + suffix).notice()).contains("재활성화할 수 없습니다");
    }

    @Test
    void 브랜드는_ACTIVE_판매상품이_있으면_409이고_계약종료는_확인값이_맞아야_하며_되돌릴_수_없다() {
        SaleProductResponse set = saleProductService.create(saleProduct("SET"), ADMIN);
        assertThatThrownBy(() -> brandService.deactivate(brandId))
                .isInstanceOf(ConflictException.class).hasMessageContaining(set.code());

        saleProductService.deactivate(set.id(), ADMIN);
        assertThatThrownBy(() -> brandService.terminate(brandId, "다른이름"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("되돌릴 수 없습니다");
        assertThat(brandService.terminate(brandId, "마스터브랜드").notice()).contains("재활성화할 수 없습니다");
        assertThatThrownBy(() -> brandService.activate(brandId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("계약종료된 브랜드 — 재활성화 불가");

        companyService.terminate(companyId, "마스터상사" + suffix);
        assertThatThrownBy(() -> companyService.activate(companyId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("재활성화 불가");
    }

    @Test
    void 제품은_구성에_포함한_ACTIVE_판매상품이_있으면_비활성화_409() {
        SaleProductResponse set = saleProductService.create(saleProduct("SET"), ADMIN);

        assertThatThrownBy(() -> productService.deactivate(toner, ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining(set.code());
        saleProductService.deactivate(set.id(), ADMIN);
        assertThat(productService.deactivate(toner, ADMIN).status()).isEqualTo(ActiveStatus.INACTIVE);
    }

    @Test
    void 구성은_주문_이력이_없을_때만_바꿀_수_있다() {
        long cream = productService.create(productRequest(brandId, "M-CREAM-" + suffix), ADMIN).id();
        SaleProductResponse set = saleProductService.create(saleProduct("SET"), ADMIN);

        SaleProductResponse changed = saleProductService.changeComposition(set.id(),
                List.of(new CompositionItemRequest(cream, 2, false)), ADMIN);
        assertThat(changed.items()).extracting(SaleProductResponse.Item::productId).containsExactly(cream);

        order("HIST", set.id());
        assertThatThrownBy(() -> saleProductService.changeComposition(set.id(),
                List.of(new CompositionItemRequest(toner, 1, false)), ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("새 판매상품으로 등록 후 매핑을 이전하세요");
    }

    @Test
    void 판매상품코드는_브랜드_안에서_유니크() {
        saleProductService.create(saleProduct("DUP"), ADMIN);
        assertThatThrownBy(() -> saleProductService.create(saleProduct("DUP"), ADMIN))
                .isInstanceOf(ConflictException.class);

        long otherBrand = brandService.create(new BrandCreateRequest(companyId, "다른브랜드")).id();
        long otherToner = productService.create(productRequest(otherBrand, "M-OTHER-" + suffix), ADMIN).id();
        assertThat(saleProductService.create(new SaleProductCreateRequest(otherBrand, "세트", "DUP-" + suffix,
                List.of(new CompositionItemRequest(otherToner, 1, false))), ADMIN).id()).as("다른 브랜드는 같은 코드 허용")
                .isPositive();
    }

    @Test
    void 판매상품_비활성화는_현재_매핑이_남아_있으면_경고한다() {
        SaleProductResponse mapped = saleProductService.create(saleProduct("MAPPED"), ADMIN);
        SaleProductResponse bare = saleProductService.create(saleProduct("BARE"), ADMIN);
        mappingService.create(new ChannelMappingRequest(qoo10, "Q-LEFT-" + suffix, "", mapped.id()), ADMIN);

        SaleProductResponse deactivated = saleProductService.deactivate(mapped.id(), ADMIN);
        assertThat(deactivated.status()).isEqualTo(ActiveStatus.INACTIVE);
        assertThat(deactivated.warnings()).singleElement().asString()
                .contains("현재 채널 매핑 1건").contains("Q-LEFT-" + suffix);
        assertThat(saleProductService.deactivate(bare.id(), ADMIN).warnings()).isEmpty();
    }

    // ------------------------------------------------------------------ 리뉴얼

    @Test
    void 리뉴얼은_새_상품_생성_매핑_재지정_구_상품_비활성화를_한_번에_하고_이력이_이어진다() {
        SaleProductResponse old = saleProductService.create(saleProduct("OLD"), ADMIN);
        mappingService.create(new ChannelMappingRequest(qoo10, "Q-OLD-" + suffix, "", old.id()), ADMIN);
        long cream = productService.create(productRequest(brandId, "M-CREAM-" + suffix), ADMIN).id();

        SaleProductRenewResponse renewed = saleProductService.renew(old.id(), new SaleProductRenewRequest(
                "NEW-" + suffix, null, List.of(new CompositionItemRequest(cream, 1, false))), ADMIN);

        assertThat(renewed.remappedMappingCount()).isEqualTo(1);
        assertThat(renewed.saleProduct().name()).as("이름 미지정이면 구 상품 이름").isEqualTo(old.name());
        assertThat(saleProductService.get(old.id(), ADMIN).status()).isEqualTo(ActiveStatus.INACTIVE);
        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT sale_product_id, effective_from, effective_to FROM sale_product_channel_mappings
                WHERE code = ? ORDER BY effective_from
                """, "Q-OLD-" + suffix);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("sale_product_id")).isEqualTo(old.id());
        assertThat(history.get(0).get("effective_to")).as("구 행 마감 = 신 행 시작").isEqualTo(history.get(1).get("effective_from"));
        assertThat(history.get(1).get("sale_product_id")).isEqualTo(renewed.saleProduct().id());
        assertThat(history.get(1).get("effective_to")).isNull();
    }

    @Test
    void 리뉴얼이_실패하면_아무것도_바뀌지_않는다() {
        SaleProductResponse old = saleProductService.create(saleProduct("OLD"), ADMIN);
        saleProductService.create(saleProduct("TAKEN"), ADMIN);
        mappingService.create(new ChannelMappingRequest(qoo10, "Q-ATOM-" + suffix, "", old.id()), ADMIN);

        assertThatThrownBy(() -> saleProductService.renew(old.id(), new SaleProductRenewRequest("TAKEN-" + suffix, null,
                List.of(new CompositionItemRequest(toner, 1, false))), ADMIN)).isInstanceOf(ConflictException.class);

        assertThat(saleProductService.get(old.id(), ADMIN).status()).isEqualTo(ActiveStatus.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_product_channel_mappings WHERE code = ? AND effective_to IS NULL AND sale_product_id = ?",
                Integer.class, "Q-ATOM-" + suffix, old.id())).isEqualTo(1);
    }

    @Test
    void 리뉴얼은_구_코드를_조건으로_쓰는_진행_중_이벤트를_경고한다() {
        SaleProductResponse old = saleProductService.create(saleProduct("EVT"), ADMIN);
        LocalDateTime now = LocalDateTime.now();
        String eventCode = giftEventService.create(new GiftEventRequest(brandId, "구코드 이벤트", GiftTimeBasis.ORDERED,
                now.minusDays(1), now.plusDays(1), null, null, GiftConditionMode.ALL, GiftGrantType.ALWAYS,
                GiftQuantityMode.FIXED, 1, null, null, null,
                List.of(new GiftEventRequest.Condition(GiftConditionTarget.SALE_PRODUCT, old.code(), null, null)),
                List.of(new GiftEventRequest.Item(toner, null, null))), ADMIN).code();

        SaleProductRenewResponse renewed = saleProductService.renew(old.id(), new SaleProductRenewRequest(
                "EVT2-" + suffix, null, List.of(new CompositionItemRequest(toner, 1, false))), ADMIN);

        assertThat(renewed.saleProduct().warnings()).anyMatch(w -> w.contains(eventCode) && w.contains(old.code()));
    }

    @Test
    void 판매상품_1개가_통관_분류_한도를_넘으면_등록_응답에_경고() {
        long category = jdbc.queryForObject("SELECT id FROM customs_categories WHERE code = 'SHEET_MASK'", Long.class);
        ProductCreateRequest maskBox = new ProductCreateRequest(brandId, "M-MASK10-" + suffix, "마스크 10매", "Mask x10",
                category, 10, null, null, null, null, null, null, null, null, null);
        long mask = productService.create(maskBox, ADMIN).id();

        SaleProductResponse ok = saleProductService.create(new SaleProductCreateRequest(brandId, "12박스", "M12-" + suffix,
                List.of(new CompositionItemRequest(mask, 12, false))), ADMIN);
        SaleProductResponse over = saleProductService.create(new SaleProductCreateRequest(brandId, "13박스", "M13-" + suffix,
                List.of(new CompositionItemRequest(mask, 13, false))), ADMIN);

        assertThat(ok.warnings()).isEmpty();
        assertThat(over.warnings()).singleElement().asString().contains("130 > 한도 120");
    }

    // ------------------------------------------------------------------ 비활성 브랜드

    @Test
    void 비활성_브랜드는_쓰기_409이고_조회와_기존_주문_취소는_된다() {
        SaleProductResponse set = saleProductService.create(saleProduct("SET"), ADMIN);
        long orderId = order("BEFORE", set.id());
        long itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, orderId);
        saleProductService.deactivate(set.id(), ADMIN);
        brandService.deactivate(brandId);

        assertThatThrownBy(() -> saleProductService.create(saleProduct("AFTER"), ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("비활성 브랜드");
        assertThatThrownBy(() -> productService.create(productRequest(brandId, "M-NEW-" + suffix), ADMIN))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> mappingService.create(new ChannelMappingRequest(qoo10, "Q-X-" + suffix, "", set.id()), ADMIN))
                .isInstanceOf(ConflictException.class);
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> giftEventService.create(new GiftEventRequest(brandId, "철수 후", GiftTimeBasis.ORDERED,
                now, now.plusDays(1), null, null, GiftConditionMode.ALL, GiftGrantType.ALWAYS, GiftQuantityMode.FIXED,
                1, null, null, null, List.of(), List.of(new GiftEventRequest.Item(toner, null, null))), ADMIN))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> seedService.seed(new ByteArrayInputStream(new byte[0]), brandId))
                .isInstanceOf(ConflictException.class);

        assertThat(saleProductService.get(set.id(), ADMIN).code()).isEqualTo(set.code());
        assertThat(productService.list(brandId, ADMIN)).isNotEmpty();
        cancellationService.cancel(orderId, List.of(new CancellationItemRequest(itemId, 1)), ADMIN);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CANCELED");
    }

    // ------------------------------------------------------------------ 사용자

    @Test
    void 사용자_등록은_role별_소속을_강제하고_이메일_변경은_로그인_아이디도_바꾸며_안내한다() {
        UserResponse staff = userService.create(new UserCreateRequest(UserRole.BRAND_STAFF, null, "b" + suffix + "@t.io",
                "브랜드담당", "password1", null, brandId));
        assertThat(staff.loginId()).isEqualTo("b" + suffix + "@t.io");
        assertThat(staff.companyId()).as("회사는 브랜드에서").isEqualTo(companyId);

        assertThatThrownBy(() -> userService.create(new UserCreateRequest(UserRole.BRAND_STAFF, null,
                "b" + suffix + "@t.io", "중복", "password1", null, brandId))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> userService.create(new UserCreateRequest(UserRole.COMPANY_STAFF, null,
                "c" + suffix + "@t.io", "회사", "password1", null, null))).isInstanceOf(InvalidRequestException.class);

        UserResponse changed = userService.update(staff.id(), new UserUpdateRequest(null, "new" + suffix + "@t.io", null, null));
        assertThat(changed.loginId()).isEqualTo("new" + suffix + "@t.io");
        assertThat(changed.notice()).isEqualTo(UserResponse.LOGIN_ID_CHANGED);
        assertThat(userService.update(staff.id(), new UserUpdateRequest("이름만", null, null, null)).notice()).isNull();
        assertThatThrownBy(() -> userService.update(staff.id(), new UserUpdateRequest(null, null, companyId, null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("role 변경은 미지원");
    }

    @Test
    void 탈퇴는_개인정보를_마스킹하고_로그인_아이디를_반납하며_재로그인할_수_없다() {
        String email = "w" + suffix + "@t.io";
        UserResponse user = userService.create(new UserCreateRequest(UserRole.COMPANY_STAFF, null, email, "탈퇴예정",
                "password1", companyId, null));

        UserResponse withdrawn = userService.withdraw(user.id(), ADMIN);

        assertThat(withdrawn.status()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(withdrawn.loginId()).startsWith("WD" + user.id() + "-");
        assertThat(withdrawn.email()).isEqualTo("withdrawn-" + user.id() + "@masked.invalid");
        assertThat(withdrawn.name()).isEqualTo("탈퇴회원");
        String hash = jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.id());
        assertThat(passwordEncoder.matches("password1", hash)).as("재로그인 불가").isFalse();
        assertThatThrownBy(() -> userService.activate(user.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> userService.resetPassword(user.id())).isInstanceOf(ConflictException.class);
        assertThat(userService.create(new UserCreateRequest(UserRole.COMPANY_STAFF, null, email, "재가입", "password1",
                companyId, null)).loginId()).as("원래 아이디 반납").isEqualTo(email);
        assertThatThrownBy(() -> userService.withdraw(1L, new AuthenticatedUser(1L, UserRole.ADMIN, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("자기 자신");
    }

    @Test
    void 비밀번호_재설정은_임시_비밀번호를_한_번_내리고_해시만_저장한다() {
        UserResponse user = userService.create(new UserCreateRequest(UserRole.ADMIN, null, "a" + suffix + "@t.io", "관리자",
                "password1", null, null));

        PasswordResetResponse reset = userService.resetPassword(user.id());

        String hash = jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.id());
        assertThat(hash).isNotEqualTo(reset.temporaryPassword());
        assertThat(passwordEncoder.matches(reset.temporaryPassword(), hash)).isTrue();
        assertThat(reset.temporaryPassword()).hasSize(12);
    }

    // ------------------------------------------------------------------ 소속 스코프 (403)

    @Test
    void 회사_직원은_자사_자원만_브랜드_직원은_자기_브랜드만() {
        long otherCompany = companyService.create(new CompanyRequest("남의상사" + suffix, null)).id();
        long otherBrand = brandService.create(new BrandCreateRequest(otherCompany, "남의브랜드")).id();
        long otherProduct = productService.create(productRequest(otherBrand, "M-ALIEN-" + suffix), ADMIN).id();
        SaleProductResponse otherSet = saleProductService.create(new SaleProductCreateRequest(otherBrand, "남의세트",
                "ALIEN-" + suffix, List.of(new CompositionItemRequest(otherProduct, 1, false))), ADMIN);
        UserResponse otherUser = userService.create(new UserCreateRequest(UserRole.COMPANY_STAFF, null,
                "o" + suffix + "@t.io", "남", "password1", otherCompany, null));
        AuthenticatedUser companyStaff = new AuthenticatedUser(900L, UserRole.COMPANY_STAFF, companyId, null);
        AuthenticatedUser brandStaff = new AuthenticatedUser(901L, UserRole.BRAND_STAFF, companyId, brandId);

        assertThatThrownBy(() -> productService.get(otherProduct, companyStaff)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> productService.create(productRequest(otherBrand, "M-X-" + suffix), companyStaff))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> saleProductService.get(otherSet.id(), brandStaff)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> saleProductService.renew(otherSet.id(), new SaleProductRenewRequest("X-" + suffix, null,
                List.of(new CompositionItemRequest(otherProduct, 1, false))), companyStaff))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> userService.get(otherUser.id(), companyStaff)).isInstanceOf(ForbiddenException.class);
        assertThat(userService.list(otherCompany, companyStaff)).as("COMPANY_STAFF는 companyId를 줘도 자사만")
                .allMatch(u -> companyId == u.companyId());
        assertThat(productService.list(null, companyStaff)).extracting(p -> p.brandId()).containsOnly(brandId);
    }

    @Test
    void 브랜드_직원은_자기_브랜드_제품만_조회한다() {
        long otherBrand = brandService.create(new BrandCreateRequest(companyId, "옆브랜드")).id();
        long otherProduct = productService.create(productRequest(otherBrand, "M-SIDE-" + suffix), ADMIN).id();
        AuthenticatedUser brandStaff = new AuthenticatedUser(901L, UserRole.BRAND_STAFF, companyId, brandId);

        assertThat(productService.get(toner, brandStaff).id()).isEqualTo(toner);
        assertThat(productService.list(null, brandStaff)).extracting(p -> p.brandId()).containsOnly(brandId);
        assertThatThrownBy(() -> productService.get(otherProduct, brandStaff)).as("같은 회사 다른 브랜드")
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> productService.list(otherBrand, brandStaff)).isInstanceOf(ForbiddenException.class);
    }

    // ------------------------------------------------------------------ fixtures

    private ProductCreateRequest productRequest(long brand, String sku) {
        return new ProductCreateRequest(brand, sku, "제품", "Product", null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private SaleProductCreateRequest saleProduct(String code) {
        return new SaleProductCreateRequest(brandId, "세트 " + code, code + "-" + suffix,
                List.of(new CompositionItemRequest(toner, 1, false)));
    }

    private long order(String key, long saleProductId) {
        registrationService.registerAll(List.of(new OrderRegistrationCommand(qoo10, suffix + "-" + key, brandId,
                BigDecimal.TEN, BigDecimal.TEN, "JPY", "주문자", "수취인", null, null, "주소", null, LocalDateTime.now(),
                List.of(Item.ofSaleProduct(saleProductId, "CODE", "", 1, BigDecimal.TEN)))));
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-" + key);
    }
}

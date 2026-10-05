package com.crossborder.oms.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.infra.jwt.TokenPayload;
import com.crossborder.oms.controller.AdminBrandController;
import com.crossborder.oms.controller.AdminCompanyController;
import com.crossborder.oms.controller.OrderController;
import com.crossborder.oms.controller.ProductController;
import com.crossborder.oms.controller.SaleProductController;
import com.crossborder.oms.controller.UserController;
import com.crossborder.oms.dto.CappedPageResponse;
import com.crossborder.oms.exception.GlobalExceptionHandler;
import com.crossborder.oms.service.order.OrderQueryService;
import com.crossborder.oms.service.order.OrderReceiverService;
import com.crossborder.oms.service.organization.BrandAdminService;
import com.crossborder.oms.service.organization.CompanyAdminService;
import com.crossborder.oms.service.organization.UserAdminService;
import com.crossborder.oms.service.product.ProductService;
import com.crossborder.oms.service.product.SaleProductService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * oms 인가 규칙: WORKER는 cbt 전용 사용자라 oms 전 API에서 403.
 * <p>
 * OmsApplication의 @EnableJpaRepositories가 웹 슬라이스에 JPA를 끌어오지 않도록 전용 설정 클래스로 띄운다.
 */
@WebMvcTest
class SecurityConfigTest {

    @SpringBootConfiguration(proxyBeanMethods = false)
    @Import({SecurityConfig.class, OrderController.class, AdminCompanyController.class, AdminBrandController.class,
            UserController.class, ProductController.class, SaleProductController.class, GlobalExceptionHandler.class})
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private OrderQueryService orderQueryService;
    @MockitoBean
    private OrderReceiverService orderReceiverService;
    @MockitoBean
    private CompanyAdminService companyAdminService;
    @MockitoBean
    private BrandAdminService brandAdminService;
    @MockitoBean
    private UserAdminService userAdminService;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private SaleProductService saleProductService;

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void WORKER는_403() throws Exception {
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token(UserRole.WORKER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/seed/template").header("Authorization", "Bearer " + token(UserRole.WORKER)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADMIN", "COMPANY_STAFF", "BRAND_STAFF"})
    void 관리자군은_통과(UserRole role) throws Exception {
        given(orderQueryService.search(any(), any(), any()))
                .willReturn(new CappedPageResponse<>(List.of(), 0, 20, 0, false));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token(role)))
                .andExpect(status().isOk());
    }

    /** 마스터 관리 URL 규칙 (role × 자원). 소속 스코프(자사·자기 브랜드)는 서비스 테스트(MasterAdminTest)가 본다 */
    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            "GET, /api/admin/companies, ADMIN, 200",
            "GET, /api/admin/companies, COMPANY_STAFF, 403",
            "GET, /api/admin/brands, BRAND_STAFF, 403",
            "GET, /api/users, ADMIN, 200",
            "GET, /api/users, COMPANY_STAFF, 200",
            "GET, /api/users, BRAND_STAFF, 403",
            "POST, /api/users/1/withdraw, COMPANY_STAFF, 403",
            "GET, /api/products, ADMIN, 200",
            "GET, /api/products, COMPANY_STAFF, 200",
            "GET, /api/products, BRAND_STAFF, 200",
            "GET, /api/products/1, BRAND_STAFF, 200",
            "POST, /api/products, BRAND_STAFF, 403",
            "POST, /api/products/1/deactivate, BRAND_STAFF, 403",
            "GET, /api/sale-products, BRAND_STAFF, 200",
            "GET, /api/sale-products, WORKER, 403",
    })
    void 마스터_관리_role_규칙(String method, String url, UserRole role, int status) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), url).header("Authorization", "Bearer " + token(role)))
                .andExpect(status().is(status));
    }

    private String token(UserRole role) {
        String token = "token-" + role;
        Long brandId = role == UserRole.BRAND_STAFF ? 2L : null;
        Long companyId = role == UserRole.COMPANY_STAFF || role == UserRole.BRAND_STAFF ? 1L : null;
        given(jwtTokenProvider.verify(token)).willReturn(new TokenPayload(5L, role, companyId, brandId));
        return token;
    }
}

package com.crossborder.oms.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.infra.jwt.TokenPayload;
import com.crossborder.oms.controller.OrderController;
import com.crossborder.oms.exception.GlobalExceptionHandler;
import com.crossborder.oms.service.order.OrderQueryService;
import com.crossborder.oms.service.order.OrderReceiverService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
    @Import({SecurityConfig.class, OrderController.class, GlobalExceptionHandler.class})
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
        given(orderQueryService.search(any(), any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token(role)))
                .andExpect(status().isOk());
    }

    private String token(UserRole role) {
        String token = "token-" + role;
        Long brandId = role == UserRole.BRAND_STAFF ? 2L : null;
        Long companyId = role == UserRole.COMPANY_STAFF || role == UserRole.BRAND_STAFF ? 1L : null;
        given(jwtTokenProvider.verify(token)).willReturn(new TokenPayload(5L, role, companyId, brandId));
        return token;
    }
}

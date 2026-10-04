package com.crossborder.oms.security.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class ScopeCheckAspectTest {

    private static final long COMPANY_A = 1L;
    private static final long COMPANY_B = 2L;
    private static final long BRAND_A1 = 10L;
    private static final long ORDER_ID = 100L;

    private final BrandRepository brandRepository = mock(BrandRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final Target target = new Target();
    private Target proxy;

    @BeforeEach
    void setUp() {
        ScopePolicy policy = new ScopePolicy(brandRepository, orderRepository, orderItemRepository);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ScopeCheckAspect(policy));
        proxy = factory.getProxy();

        Brand brand = Brand.create(COMPANY_A, "브랜드A1");
        ReflectionTestUtils.setField(brand, "id", BRAND_A1);
        given(brandRepository.findById(BRAND_A1)).willReturn(Optional.of(brand));

        Order order = Order.builder().companyId(COMPANY_A).build();
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 없는_주문은_404이고_메서드를_실행하지_않는다() {
        login(UserRole.ADMIN, null, null);

        assertThatThrownBy(() -> proxy.order(999L)).isInstanceOf(NotFoundException.class);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    void 타사_주문은_403() {
        login(UserRole.COMPANY_STAFF, COMPANY_B, null);

        assertThatThrownBy(() -> proxy.order(ORDER_ID)).isInstanceOf(ForbiddenException.class);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    void 자기_브랜드_항목이_있는_주문은_통과() {
        login(UserRole.BRAND_STAFF, COMPANY_A, BRAND_A1);
        given(orderItemRepository.existsByOrderIdAndBrandId(ORDER_ID, BRAND_A1)).willReturn(true);

        proxy.order(ORDER_ID);

        assertThat(target.calls).hasValue(1);
    }

    @Test
    void 브랜드는_존재확인_후_스코프확인() {
        login(UserRole.COMPANY_STAFF, COMPANY_B, null);

        assertThatThrownBy(() -> proxy.brand(999L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> proxy.brand(BRAND_A1)).isInstanceOf(ForbiddenException.class);

        login(UserRole.COMPANY_STAFF, COMPANY_A, null);
        proxy.brand(BRAND_A1);
        assertThat(target.calls).hasValue(1);
    }

    @Test
    void WORKER는_브랜드_주문_모두_불가() {
        login(UserRole.WORKER, null, null);

        assertThatThrownBy(() -> proxy.brand(BRAND_A1)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> proxy.order(ORDER_ID)).isInstanceOf(ForbiddenException.class);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    void ScopeId가_없으면_설정_오류() {
        login(UserRole.ADMIN, null, null);

        assertThatThrownBy(() -> proxy.missingScopeId(ORDER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void login(UserRole role, Long companyId, Long brandId) {
        AuthenticatedUser user = new AuthenticatedUser(1L, role, companyId, brandId);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    static class Target {

        final AtomicInteger calls = new AtomicInteger();

        @ScopeCheck(ScopeTarget.ORDER)
        public void order(@ScopeId Long orderId) {
            calls.incrementAndGet();
        }

        @ScopeCheck(ScopeTarget.BRAND)
        public void brand(@ScopeId Long brandId) {
            calls.incrementAndGet();
        }

        @ScopeCheck(ScopeTarget.ORDER)
        public void missingScopeId(Long orderId) {
            calls.incrementAndGet();
        }
    }
}

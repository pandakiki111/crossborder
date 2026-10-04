package com.crossborder.oms.security.scope;

import com.crossborder.oms.security.AuthenticatedUser;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * @ScopeCheck 메서드 실행 전 스코프 검사. 사용자는 메서드 인자가 아니라 SecurityContext의 principal에서 꺼낸다.
 * 트랜잭션(기본 LOWEST_PRECEDENCE)보다 먼저 돌아 검사 실패 시 트랜잭션을 열지 않는다.
 * HIGHEST_PRECEDENCE는 쓰지 않는다 — ExposeInvocationInterceptor보다 앞서면 어노테이션 인자 바인딩이 실패한다.
 */
@Aspect
@Component
@Order(0)
public class ScopeCheckAspect {

    private final ScopePolicy scopePolicy;

    public ScopeCheckAspect(ScopePolicy scopePolicy) {
        this.scopePolicy = scopePolicy;
    }

    @Before("@annotation(scopeCheck)")
    public void check(JoinPoint joinPoint, ScopeCheck scopeCheck) {
        Long id = scopeId(joinPoint);
        scopePolicy.check(scopeCheck.value(), id, currentUser());
    }

    private static Long scopeId(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Long found = null;
        int count = 0;
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof ScopeId) {
                    found = (Long) joinPoint.getArgs()[i];
                    count++;
                }
            }
        }
        if (count != 1) {
            throw new IllegalArgumentException("@ScopeCheck 메서드에는 @ScopeId 파라미터가 정확히 1개 있어야 합니다. method=" + method);
        }
        return found;
    }

    private static AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalArgumentException("인증 사용자 없이 @ScopeCheck 메서드가 호출되었습니다.");
        }
        return user;
    }
}

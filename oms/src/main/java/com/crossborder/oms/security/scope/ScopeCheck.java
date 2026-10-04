package com.crossborder.oms.security.scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 실행 전 @ScopeId 파라미터가 가리키는 대상이 인증 사용자의 소속 범위 안인지 검사한다 (ScopeCheckAspect).
 * 대상이 없으면 404, 범위 밖이면 403. 규칙 자체는 ScopePolicy에 있다.
 * <p>
 * 요청 파라미터로 들어온 id에 쓴다. 엔티티에서 꺼낸 id(매핑의 brandId 등)는 ScopePolicy를 직접 호출한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ScopeCheck {

    ScopeTarget value();
}

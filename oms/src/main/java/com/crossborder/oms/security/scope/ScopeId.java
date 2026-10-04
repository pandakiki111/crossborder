package com.crossborder.oms.security.scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @ScopeCheck 메서드에서 검사 대상 id 파라미터 (Long). 메서드당 정확히 1개.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ScopeId {
}

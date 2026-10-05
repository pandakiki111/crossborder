package com.crossborder.infra.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시는 BCrypt (V4·로컬 시딩 해시와 같은 형식). auth(로그인 검증)와 oms(사용자 관리·비밀번호 재설정)가 공유한다.
 */
@AutoConfiguration
@ConditionalOnClass(PasswordEncoder.class)
public class PasswordConfig {

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

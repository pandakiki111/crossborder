package com.crossborder.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// 로그인은 AuthService가 직접 처리하므로 기본 in-memory 사용자(generated security password)는 만들지 않는다
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
// 리포지토리는 각 모듈이 소유한다. 기본 스캔에 기대지 않고 자기 패키지만 명시해 다른 모듈 리포지토리가 섞이지 않게 한다
@EnableJpaRepositories("com.crossborder.auth")
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}

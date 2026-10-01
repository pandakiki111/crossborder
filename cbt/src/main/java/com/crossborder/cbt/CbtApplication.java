package com.crossborder.cbt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories("com.crossborder")
public class CbtApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbtApplication.class, args);
    }
}

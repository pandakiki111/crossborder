package com.crossborder.auth.controller;

import com.crossborder.auth.dto.LoginRequest;
import com.crossborder.auth.dto.LoginResponse;
import com.crossborder.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * TODO: 프록시(LB) 뒤에 배포하면 getRemoteAddr()가 프록시 IP가 되므로 server.forward-headers-strategy 설정 필요
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }
}

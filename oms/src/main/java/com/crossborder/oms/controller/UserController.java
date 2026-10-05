package com.crossborder.oms.controller;

import com.crossborder.oms.dto.organization.PasswordResetResponse;
import com.crossborder.oms.dto.organization.UserCreateRequest;
import com.crossborder.oms.dto.organization.UserResponse;
import com.crossborder.oms.dto.organization.UserUpdateRequest;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.organization.UserAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 관리. 조회는 ADMIN·COMPANY_STAFF(자사), 나머지는 ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserAdminService userAdminService;

    public UserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdminService.create(request));
    }

    @GetMapping
    public List<UserResponse> list(@RequestParam(required = false) Long companyId,
                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return userAdminService.list(companyId, user);
    }

    @GetMapping("/{userId}")
    public UserResponse get(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser user) {
        return userAdminService.get(userId, user);
    }

    /** 이메일이 바뀌어 로그인 아이디가 바뀌면 응답 notice로 안내 */
    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable Long userId, @Valid @RequestBody UserUpdateRequest request) {
        return userAdminService.update(userId, request);
    }

    @PostMapping("/{userId}/deactivate")
    public UserResponse deactivate(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser user) {
        return userAdminService.deactivate(userId, user);
    }

    @PostMapping("/{userId}/activate")
    public UserResponse activate(@PathVariable Long userId) {
        return userAdminService.activate(userId);
    }

    /** 탈퇴 + 개인정보 마스킹 (즉시, 되돌릴 수 없음) */
    @PostMapping("/{userId}/withdraw")
    public UserResponse withdraw(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser user) {
        return userAdminService.withdraw(userId, user);
    }

    /** 임시 비밀번호 발급 (응답에서 한 번만 확인 가능) */
    @PostMapping("/{userId}/password-reset")
    public PasswordResetResponse resetPassword(@PathVariable Long userId) {
        return userAdminService.resetPassword(userId);
    }
}

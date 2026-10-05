package com.crossborder.oms.dto.organization;

import com.crossborder.common.entity.organization.User;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.common.entity.organization.UserStatus;
import java.time.LocalDateTime;

/**
 * 사용자 (비밀번호·OTP 시크릿은 내리지 않는다).
 *
 * @param notice 이메일 변경으로 로그인 아이디가 바뀌었으면 안내, 아니면 null
 */
public record UserResponse(Long id, String loginId, String email, String name, UserRole role, Long companyId,
                           Long brandId, UserStatus status, LocalDateTime withdrawnAt, String notice) {

    public static final String LOGIN_ID_CHANGED = "로그인 아이디가 변경되었습니다. 새 이메일로 로그인하세요.";

    public static UserResponse from(User user) {
        return from(user, null);
    }

    public static UserResponse from(User user, String notice) {
        return new UserResponse(user.getId(), user.getLoginId(), user.getEmail(), user.getName(), user.getRole(),
                user.getCompanyId(), user.getBrandId(), user.getStatus(), user.getDeletedAt(), notice);
    }
}

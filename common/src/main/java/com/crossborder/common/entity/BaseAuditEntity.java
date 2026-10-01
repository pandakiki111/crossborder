package com.crossborder.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/**
 * created_user_id / updated_user_id 컬럼을 가진 테이블용 베이스.
 * 값은 AuditorAware&lt;Long&gt; 빈이 채운다.
 */
@Getter
@MappedSuperclass
public abstract class BaseAuditEntity extends BaseEntity {

    @CreatedBy
    @Column(name = "created_user_id", nullable = false, updatable = false)
    private Long createdUserId;

    @LastModifiedBy
    @Column(name = "updated_user_id")
    private Long updatedUserId;
}

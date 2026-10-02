package com.crossborder.auth.repository;

import com.crossborder.common.entity.auth.LoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {
}

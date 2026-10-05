package com.crossborder.oms.repository;

import com.crossborder.common.entity.organization.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByLoginId(String loginId);

    List<User> findAllByOrderByIdAsc();

    List<User> findByCompanyIdOrderByIdAsc(Long companyId);
}

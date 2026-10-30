package com.weatherplatform.authservice.repository;

import com.weatherplatform.authservice.entity.User;
import com.weatherplatform.authservice.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Drives the one-time administrator bootstrap on an empty platform. */
    boolean existsByRole(UserRole role);

    long countByRole(UserRole role);

    List<User> findAllByOrderByCreatedAtDesc();

    List<User> findByRoleOrderByCreatedAtDesc(UserRole role);
}

package com.rohit8020.authservice.repository;

import com.rohit8020.authservice.entity.User;
import com.rohit8020.authservice.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    long countByRole(UserRole role);
    List<User> findAllByOrderByIdAsc();
}

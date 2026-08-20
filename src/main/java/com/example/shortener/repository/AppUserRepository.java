package com.example.shortener.repository;

import com.example.shortener.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Case-insensitive, matching the functional unique index on LOWER(username). */
    @Query("SELECT u FROM AppUser u WHERE LOWER(u.username) = LOWER(:username)")
    Optional<AppUser> findByUsername(@Param("username") String username);
}

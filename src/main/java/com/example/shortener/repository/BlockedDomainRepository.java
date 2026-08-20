package com.example.shortener.repository;

import com.example.shortener.domain.BlockedDomain;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedDomainRepository extends JpaRepository<BlockedDomain, String> {
}

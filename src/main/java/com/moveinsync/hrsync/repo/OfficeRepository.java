package com.moveinsync.hrsync.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OfficeRepository extends JpaRepository<OfficeEntity, UUID> {

    boolean existsByTenantIdAndCode(String tenantId, String code);
}

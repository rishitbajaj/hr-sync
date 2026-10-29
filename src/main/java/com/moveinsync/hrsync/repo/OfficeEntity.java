package com.moveinsync.hrsync.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "office")
public class OfficeEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String code;

    protected OfficeEntity() {
    }

    public OfficeEntity(UUID id, String tenantId, String code) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
    }
}

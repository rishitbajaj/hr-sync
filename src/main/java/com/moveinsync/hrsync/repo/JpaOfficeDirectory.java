package com.moveinsync.hrsync.repo;

import com.moveinsync.hrsync.domain.OfficeDirectory;
import org.springframework.stereotype.Component;

@Component
public class JpaOfficeDirectory implements OfficeDirectory {

    private final OfficeRepository offices;

    public JpaOfficeDirectory(OfficeRepository offices) {
        this.offices = offices;
    }

    @Override
    public boolean exists(String tenantId, String officeCode) {
        return offices.existsByTenantIdAndCode(tenantId, officeCode);
    }
}

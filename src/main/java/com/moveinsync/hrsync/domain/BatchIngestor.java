package com.moveinsync.hrsync.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BatchIngestor {

    private final EventValidator validator;
    private final OfficeDirectory offices;
    private final EmployeeSyncStore employees;
    private final Clock clock;
    private final RecordWork recordWork;

    public BatchIngestor(
            EventValidator validator,
            OfficeDirectory offices,
            EmployeeSyncStore employees,
            Clock clock,
            RecordWork recordWork
    ) {
        this.validator = validator;
        this.offices = offices;
        this.employees = employees;
        this.clock = clock;
        this.recordWork = recordWork;
    }

    public List<IngestResult> ingest(List<SyncEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<IngestResult> results = new ArrayList<>(events.size());
        for (SyncEvent event : events) {
            results.add(ingestOne(event));
        }
        return List.copyOf(results);
    }

    private IngestResult ingestOne(SyncEvent event) {
        try {
            if (event == null) {
                return IngestResult.rejected(null, null, null, ReasonCode.MISSING_REQUIRED_FIELD);
            }
            Optional<ReasonCode> problem = validator.validate(event, offices);
            if (problem.isPresent()) {
                return rejected(event, problem.get());
            }
            return recordWork.call(() -> apply(event));
        } catch (Exception ex) {
            return rejected(event, ReasonCode.APPLY_FAILED);
        }
    }

    private IngestResult apply(SyncEvent event) {
        Instant now = clock.instant();
        Optional<HrEmployee> existing = employees.find(event.tenantId(), event.employeeId());
        switch (event.type()) {
            case CREATE -> {
                if (existing.isPresent()) {
                    return rejected(event, ReasonCode.ALREADY_EXISTS);
                }
                employees.save(HrEmployee.createFrom(event, now));
            }
            case UPDATE -> {
                if (existing.isEmpty()) {
                    return rejected(event, ReasonCode.EMPLOYEE_NOT_FOUND);
                }
                HrEmployee employee = existing.get();
                employee.applyHrUpdate(event, now);
                employees.save(employee);
            }
            case DEACTIVATE -> {
                if (existing.isEmpty()) {
                    return rejected(event, ReasonCode.EMPLOYEE_NOT_FOUND);
                }
                HrEmployee employee = existing.get();
                employee.deactivate(event.sourceVersion(), now);
                employees.save(employee);
            }
        }
        return IngestResult.applied(event.tenantId(), event.employeeId(), event.sourceVersion());
    }

    private static IngestResult rejected(SyncEvent event, ReasonCode reasonCode) {
        if (event == null) {
            return IngestResult.rejected(null, null, null, reasonCode);
        }
        return IngestResult.rejected(event.tenantId(), event.employeeId(), event.sourceVersion(), reasonCode);
    }
}

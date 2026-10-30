package com.moveinsync.hrsync.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.domain.ChangeCommand;
import com.moveinsync.hrsync.domain.ChangedFields;
import com.moveinsync.hrsync.domain.ChangeStore;
import com.moveinsync.hrsync.domain.Decision;
import com.moveinsync.hrsync.domain.EmployeeChangeApplier;
import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.HrEmployee;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.RecordWork;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmployeeChangeService implements ChangeStore {

    private static final int ATTEMPTS = 5;

    private static final String FIND = """
            select id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                   pickup_point, special_assistance, hr_version, transport_version, status, updated_at
            from employee
            where tenant_id = ? and employee_id = ?
            for update
            """;

    private static final String INSERT_EMPLOYEE = """
            insert into employee (
                id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                pickup_point, special_assistance, hr_version, transport_version, status, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_HR = """
            update employee
            set name = ?, email = ?, phone = ?, manager = ?, office_code = ?, shift = ?,
                hr_version = ?, status = ?, updated_at = ?
            where tenant_id = ? and employee_id = ? and hr_version < ?
            """;

    private static final String UPDATE_TRANSPORT = """
            update employee
            set pickup_point = ?, special_assistance = ?, transport_version = ?, updated_at = ?
            where tenant_id = ? and employee_id = ? and transport_version < ?
            """;

    private static final String INSERT_PROCESSED = """
            insert into processed_event (id, tenant_id, employee_id, channel, source_version, processed_at)
            values (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_OUTBOX = """
            insert into outbox (tenant_id, aggregate_id, event_type, payload, created_at)
            values (?, ?, ?, cast(? as jsonb), ?)
            """;

    private final EmployeeChangeApplier applier;
    private final JdbcTemplate jdbc;
    private final RecordWork recordWork;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final PersistGate persistGate;

    public EmployeeChangeService(
            JdbcTemplate jdbc,
            RecordWork recordWork,
            Clock clock,
            ObjectMapper objectMapper,
            PersistGate persistGate
    ) {
        this.applier = new EmployeeChangeApplier(clock);
        this.jdbc = jdbc;
        this.recordWork = recordWork;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.persistGate = persistGate;
    }

    @Override
    public IngestResult apply(ChangeCommand command) {
        DataAccessException uniqueViolation = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return recordWork.call(() -> applyOnce(command));
            } catch (DataAccessException exception) {
                if (!isUniqueViolation(exception)) {
                    throw exception;
                }
                uniqueViolation = exception;
            }
        }
        throw uniqueViolation;
    }

    private IngestResult applyOnce(ChangeCommand command) {
        if (alreadyProcessed(command)) {
            return IngestResult.ignored(command.tenantId(), command.employeeId(), command.version(), ReasonCode.DUPLICATE);
        }
        HrEmployee current = lockEmployee(command.tenantId(), command.employeeId()).orElse(null);
        Decision decision = applier.decide(current, false, command);
        if (decision.kind() == Decision.Kind.IGNORED) {
            return IngestResult.ignored(command.tenantId(), command.employeeId(), command.version(), decision.reason());
        }
        HrEmployee next = decision.state();
        if (current == null) {
            insertEmployee(next);
        } else {
            int updated = conditionalUpdate(next, command.channel());
            if (updated == 0) {
                return IngestResult.ignored(
                        command.tenantId(),
                        command.employeeId(),
                        command.version(),
                        ReasonCode.STALE_IGNORED
                );
            }
        }
        insertProcessed(command);
        insertOutbox(command, current, next);
        persistGate.afterPersist();
        return IngestResult.applied(command.tenantId(), command.employeeId(), command.version());
    }

    private boolean alreadyProcessed(ChangeCommand command) {
        Long count = jdbc.queryForObject(
                """
                        select count(*) from processed_event
                        where tenant_id = ? and employee_id = ? and channel = ? and source_version = ?
                        """,
                Long.class,
                command.tenantId(),
                command.employeeId(),
                command.channel().name(),
                command.version()
        );
        return count != null && count > 0;
    }

    private Optional<HrEmployee> lockEmployee(String tenantId, String employeeId) {
        List<HrEmployee> rows = jdbc.query(FIND, (rs, row) -> mapEmployee(rs), tenantId, employeeId);
        return rows.stream().findFirst();
    }

    private void insertEmployee(HrEmployee employee) {
        jdbc.update(
                INSERT_EMPLOYEE,
                employee.id() == null ? UUID.randomUUID() : employee.id(),
                employee.tenantId(),
                employee.employeeId(),
                employee.name(),
                employee.email(),
                employee.phone(),
                employee.manager(),
                employee.officeCode(),
                employee.shift(),
                employee.pickupPoint(),
                employee.specialAssistance(),
                employee.hrVersion(),
                employee.transportVersion(),
                employee.status().name(),
                Timestamp.from(employee.updatedAt())
        );
    }

    private int conditionalUpdate(HrEmployee employee, FieldChannel channel) {
        if (channel == FieldChannel.HR) {
            return jdbc.update(
                    UPDATE_HR,
                    employee.name(),
                    employee.email(),
                    employee.phone(),
                    employee.manager(),
                    employee.officeCode(),
                    employee.shift(),
                    employee.hrVersion(),
                    employee.status().name(),
                    Timestamp.from(employee.updatedAt()),
                    employee.tenantId(),
                    employee.employeeId(),
                    employee.hrVersion()
            );
        }
        return jdbc.update(
                UPDATE_TRANSPORT,
                employee.pickupPoint(),
                employee.specialAssistance(),
                employee.transportVersion(),
                Timestamp.from(employee.updatedAt()),
                employee.tenantId(),
                employee.employeeId(),
                employee.transportVersion()
        );
    }

    private void insertOutbox(ChangeCommand command, HrEmployee before, HrEmployee after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", command.employeeId());
        payload.put("changedFields", ChangedFields.between(before, after, command.channel()));
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write outbox payload");
        }
        jdbc.update(
                INSERT_OUTBOX,
                command.tenantId(),
                command.employeeId(),
                command.type().name(),
                json,
                Timestamp.from(clock.instant())
        );
    }

    private void insertProcessed(ChangeCommand command) {
        jdbc.update(
                INSERT_PROCESSED,
                UUID.randomUUID(),
                command.tenantId(),
                command.employeeId(),
                command.channel().name(),
                command.version(),
                Timestamp.from(clock.instant())
        );
    }

    private static HrEmployee mapEmployee(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return HrEmployee.rehydrate(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("employee_id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("manager"),
                rs.getString("office_code"),
                rs.getString("shift"),
                rs.getString("pickup_point"),
                rs.getString("special_assistance"),
                rs.getLong("hr_version"),
                rs.getLong("transport_version"),
                EmployeeStatus.valueOf(rs.getString("status")),
                updatedAt.toInstant()
        );
    }

    private static boolean isUniqueViolation(DataAccessException exception) {
        if (exception instanceof DuplicateKeyException) {
            return true;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

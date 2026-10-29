package com.moveinsync.hrsync;

import com.moveinsync.hrsync.domain.ChangeCommand;
import com.moveinsync.hrsync.domain.ChangeStore;
import com.moveinsync.hrsync.domain.Decision;
import com.moveinsync.hrsync.domain.EmployeeChangeApplier;
import com.moveinsync.hrsync.domain.HrEmployee;
import com.moveinsync.hrsync.domain.IngestResult;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

final class InMemoryChangeStore implements ChangeStore {

    final Map<String, HrEmployee> rows = new HashMap<>();
    private final Set<String> processed = new HashSet<>();
    private final EmployeeChangeApplier applier = new EmployeeChangeApplier(Clock.systemUTC());
    private Consumer<ChangeCommand> beforeApply = command -> {
    };

    void failWhen(Consumer<ChangeCommand> beforeApply) {
        this.beforeApply = beforeApply;
    }

    void put(HrEmployee employee) {
        rows.put(key(employee.tenantId(), employee.employeeId()), employee);
    }

    Optional<HrEmployee> find(String tenantId, String employeeId) {
        return Optional.ofNullable(rows.get(key(tenantId, employeeId)));
    }

    @Override
    public IngestResult apply(ChangeCommand command) {
        beforeApply.accept(command);
        String processedKey = command.tenantId() + "|" + command.employeeId() + "|" + command.channel() + "|" + command.version();
        HrEmployee current = rows.get(key(command.tenantId(), command.employeeId()));
        Decision decision = applier.decide(current, processed.contains(processedKey), command);
        if (decision.kind() == Decision.Kind.IGNORED) {
            return IngestResult.ignored(command.tenantId(), command.employeeId(), command.version(), decision.reason());
        }
        rows.put(key(command.tenantId(), command.employeeId()), decision.state());
        processed.add(processedKey);
        return IngestResult.applied(command.tenantId(), command.employeeId(), command.version());
    }

    private static String key(String tenantId, String employeeId) {
        return tenantId + "\0" + employeeId;
    }
}

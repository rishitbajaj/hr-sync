package com.moveinsync.hrsync.domain;

import java.time.Clock;
import java.time.Instant;

public final class EmployeeChangeApplier {

    private final Clock clock;

    public EmployeeChangeApplier(Clock clock) {
        this.clock = clock;
    }

    public Decision decide(HrEmployee current, boolean alreadyProcessed, ChangeCommand command) {
        if (alreadyProcessed) {
            return Decision.ignored(ReasonCode.DUPLICATE);
        }
        if (current != null && command.version() <= current.version(command.channel())) {
            return Decision.ignored(ReasonCode.STALE_IGNORED);
        }
        return Decision.apply(merge(current, command, clock.instant()));
    }

    private static HrEmployee merge(HrEmployee current, ChangeCommand command, Instant now) {
        if (current == null) {
            return firstSighting(command, now);
        }
        return switch (command.channel()) {
            case HR -> mergeHr(current, command, now);
            case TRANSPORT -> mergeTransport(current, command, now);
        };
    }

    private static HrEmployee firstSighting(ChangeCommand command, Instant now) {
        EmployeeFields fields = command.fields();
        if (command.channel() == FieldChannel.TRANSPORT) {
            return HrEmployee.rehydrate(
                    null,
                    command.tenantId(),
                    command.employeeId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    fields.pickupPoint(),
                    fields.specialAssistance(),
                    0,
                    command.version(),
                    EmployeeStatus.PLACEHOLDER,
                    now
            );
        }
        boolean deactivate = command.type() == EventType.DEACTIVATE;
        EmployeeStatus status = switch (command.type()) {
            case CREATE -> EmployeeStatus.ACTIVE;
            case DEACTIVATE -> EmployeeStatus.DEACTIVATED;
            case UPDATE -> EmployeeStatus.PLACEHOLDER;
        };
        return HrEmployee.rehydrate(
                null,
                command.tenantId(),
                command.employeeId(),
                deactivate ? null : fields.name(),
                deactivate ? null : fields.email(),
                deactivate ? null : fields.phone(),
                deactivate ? null : fields.manager(),
                deactivate ? null : fields.officeCode(),
                deactivate ? null : fields.shift(),
                null,
                null,
                command.version(),
                0,
                status,
                now
        );
    }

    private static HrEmployee mergeHr(HrEmployee current, ChangeCommand command, Instant now) {
        EmployeeFields fields = command.fields();
        boolean create = command.type() == EventType.CREATE;
        boolean deactivate = command.type() == EventType.DEACTIVATE;
        return HrEmployee.rehydrate(
                current.id(),
                current.tenantId(),
                current.employeeId(),
                deactivate ? current.name() : prefer(fields.name(), current.name()),
                deactivate ? current.email() : prefer(fields.email(), current.email()),
                deactivate ? current.phone() : prefer(fields.phone(), current.phone()),
                deactivate ? current.manager() : prefer(fields.manager(), current.manager()),
                deactivate ? current.officeCode() : prefer(fields.officeCode(), current.officeCode()),
                deactivate ? current.shift() : prefer(fields.shift(), current.shift()),
                current.pickupPoint(),
                current.specialAssistance(),
                command.version(),
                current.transportVersion(),
                deactivate ? EmployeeStatus.DEACTIVATED : (create ? EmployeeStatus.ACTIVE : current.status()),
                now
        );
    }

    private static HrEmployee mergeTransport(HrEmployee current, ChangeCommand command, Instant now) {
        EmployeeFields fields = command.fields();
        return HrEmployee.rehydrate(
                current.id(),
                current.tenantId(),
                current.employeeId(),
                current.name(),
                current.email(),
                current.phone(),
                current.manager(),
                current.officeCode(),
                current.shift(),
                prefer(fields.pickupPoint(), current.pickupPoint()),
                prefer(fields.specialAssistance(), current.specialAssistance()),
                current.hrVersion(),
                command.version(),
                current.status(),
                now
        );
    }

    private static String prefer(String incoming, String current) {
        return incoming == null ? current : incoming;
    }
}

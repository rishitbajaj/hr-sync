package com.moveinsync.hrsync.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference model applies the same rules as {@link EmployeeChangeApplier}: a version
 * applies only when it is strictly newer for its channel, duplicates of an applied
 * version change nothing, and each channel writes only its own fields. Folding in
 * version order is what a batch does after it sorts. One event at a time is not the
 * same for every arrival order, because a higher version first makes the lower one
 * stale and that stale event writes nothing.
 */
class EmployeeChangePropertiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    private static final String TENANT = "t1";

    private final EmployeeChangeApplier applier = new EmployeeChangeApplier(CLOCK);

    @Property(seed = "424242")
    void finalStateMatchesTheHighestVersionModel(@ForAll("sequences") List<ChangeCommand> events) {
        assertThat(apply(applier, events).snapshot()).isEqualTo(model(events));
    }

    @Property(seed = "424242")
    void replayingTheSequenceChangesNothing(@ForAll("sequences") List<ChangeCommand> events) {
        Run first = apply(applier, events);
        Run second = apply(applier, events, first);
        assertThat(second.applied()).isZero();
        assertThat(second.snapshot()).isEqualTo(first.snapshot());
    }

    @Property(seed = "424242")
    void versionOrderIsTheSameForEveryPermutation(@ForAll("sequences") List<ChangeCommand> events) {
        Assume.that(!conflictingSameVersion(events));
        List<ChangeCommand> shuffled = new ArrayList<>(events);
        java.util.Collections.shuffle(shuffled, new java.util.Random(424242));
        assertThat(model(versionOrder(events))).isEqualTo(model(versionOrder(shuffled)));
        assertThat(apply(applier, versionOrder(events)).snapshot())
                .isEqualTo(apply(applier, versionOrder(shuffled)).snapshot());
    }

    @Property(seed = "424242")
    void everyArrivalOrderKeepsTheHighestVersion(@ForAll("sequences") List<ChangeCommand> events) {
        List<ChangeCommand> shuffled = new ArrayList<>(events);
        java.util.Collections.shuffle(shuffled, new java.util.Random(424243));
        Map<String, Snapshot> state = apply(applier, shuffled).snapshot();
        for (String employeeId : employeeIds(events)) {
            Snapshot row = state.get(employeeId);
            long hr = maxVersion(events, employeeId, FieldChannel.HR);
            long transport = maxVersion(events, employeeId, FieldChannel.TRANSPORT);
            if (hr == 0 && transport == 0) {
                assertThat(row).isNull();
                continue;
            }
            assertThat(row).isNotNull();
            if (hr > 0) {
                assertThat(row.hrVersion()).isEqualTo(hr);
            }
            if (transport > 0) {
                assertThat(row.transportVersion()).isEqualTo(transport);
            }
        }
    }

    @Property(seed = "424242")
    void channelsDoNotWriteEachOthersFields(@ForAll("sequences") List<ChangeCommand> events) {
        Map<String, HrEmployee> rows = new HashMap<>();
        Set<String> processed = new HashSet<>();
        for (ChangeCommand command : events) {
            HrEmployee before = rows.get(command.employeeId());
            Decision decision = applier.decide(before, processed.contains(key(command)), command);
            if (decision.kind() != Decision.Kind.APPLY) {
                continue;
            }
            HrEmployee after = decision.state();
            if (command.channel() == FieldChannel.HR) {
                assertThat(after.pickupPoint()).isEqualTo(before == null ? null : before.pickupPoint());
                assertThat(after.specialAssistance()).isEqualTo(before == null ? null : before.specialAssistance());
                assertThat(after.transportVersion()).isEqualTo(before == null ? 0 : before.transportVersion());
            } else {
                assertThat(after.name()).isEqualTo(before == null ? null : before.name());
                assertThat(after.email()).isEqualTo(before == null ? null : before.email());
                assertThat(after.phone()).isEqualTo(before == null ? null : before.phone());
                assertThat(after.manager()).isEqualTo(before == null ? null : before.manager());
                assertThat(after.officeCode()).isEqualTo(before == null ? null : before.officeCode());
                assertThat(after.shift()).isEqualTo(before == null ? null : before.shift());
                assertThat(after.hrVersion()).isEqualTo(before == null ? 0 : before.hrVersion());
                assertThat(after.status()).isEqualTo(before == null ? EmployeeStatus.PLACEHOLDER : before.status());
            }
            rows.put(command.employeeId(), after);
            processed.add(key(command));
        }
    }

    @Property(seed = "424242")
    void staleAndDuplicateEventsAreNeverApplied(@ForAll("sequences") List<ChangeCommand> events) {
        Map<String, HrEmployee> rows = new HashMap<>();
        Set<String> processed = new HashSet<>();
        for (ChangeCommand command : events) {
            HrEmployee before = rows.get(command.employeeId());
            boolean duplicate = processed.contains(key(command));
            boolean stale = before != null && command.version() <= before.version(command.channel());
            Decision decision = applier.decide(before, duplicate, command);
            if (duplicate || stale) {
                assertThat(decision.kind()).isNotEqualTo(Decision.Kind.APPLY);
                assertThat(decision.state()).isNull();
                continue;
            }
            if (decision.kind() == Decision.Kind.APPLY) {
                Decision withoutIdempotencyRow = applier.decide(decision.state(), false, command);
                assertThat(withoutIdempotencyRow.kind()).isNotEqualTo(Decision.Kind.APPLY);
                rows.put(command.employeeId(), decision.state());
                processed.add(key(command));
            }
        }
    }

    @Property(seed = "424242")
    void aLowerDeactivateIsIgnored(@ForAll("sequences") List<ChangeCommand> events, @ForAll("versions") long lower) {
        Run run = apply(applier, events);
        for (String employeeId : employeeIds(events)) {
            Snapshot row = run.snapshot().get(employeeId);
            if (row == null || lower >= row.hrVersion()) {
                continue;
            }
            ChangeCommand deactivate = new ChangeCommand(
                    TENANT, employeeId, lower, EventType.DEACTIVATE, FieldChannel.HR, EmployeeFields.empty()
            );
            Decision decision = applier.decide(run.rows().get(employeeId), false, deactivate);
            assertThat(decision.kind()).isEqualTo(Decision.Kind.IGNORED);
            assertThat(decision.reason()).isEqualTo(ReasonCode.STALE_IGNORED);
        }
    }

    @Test
    void breakingTheVersionComparisonFailsTheStaleProperty() {
        ChangeCommand first = hr(5, "e1", EventType.CREATE, "Ada", "BLR");
        ChangeCommand equal = hr(5, "e1", EventType.UPDATE, "Other", "HYD");
        HrEmployee stored = applier.decide(null, false, first).state();
        Decision broken = brokenVersionComparison(stored, equal);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> {
            boolean stale = equal.version() <= stored.version(equal.channel());
            assertThat(stale).isTrue();
            assertThat(broken.kind()).isNotEqualTo(Decision.Kind.APPLY);
        });
        assertThat(applier.decide(stored, false, equal).kind()).isEqualTo(Decision.Kind.IGNORED);
    }

    @Test
    void breakingOwnershipCopiesATransportFieldOntoAnHrEvent() {
        HrEmployee current = HrEmployee.rehydrate(
                null, TENANT, "e1", "Ada", null, null, null, "BLR", "DAY",
                "Gate 4", "wheelchair", 1, 3, EmployeeStatus.ACTIVE, Instant.EPOCH
        );
        ChangeCommand command = hr(2, "e1", EventType.UPDATE, "Ada", "Stolen gate");
        HrEmployee broken = HrEmployee.rehydrate(
                current.id(), current.tenantId(), current.employeeId(),
                "Ada", current.email(), current.phone(), current.manager(), current.officeCode(), current.shift(),
                "Stolen gate", current.specialAssistance(),
                2, current.transportVersion(), current.status(), Instant.EPOCH
        );
        HrEmployee actual = applier.decide(current, false, command).state();
        assertThat(actual.pickupPoint()).isEqualTo("Gate 4");
        assertThat(broken.pickupPoint()).isNotEqualTo(actual.pickupPoint());
    }

    private Decision brokenVersionComparison(HrEmployee current, ChangeCommand command) {
        if (current != null && command.version() < current.version(command.channel())) {
            return Decision.ignored(ReasonCode.STALE_IGNORED);
        }
        HrEmployee lowered = current == null ? null : HrEmployee.rehydrate(
                current.id(), current.tenantId(), current.employeeId(),
                current.name(), current.email(), current.phone(), current.manager(),
                current.officeCode(), current.shift(), current.pickupPoint(), current.specialAssistance(),
                command.channel() == FieldChannel.HR ? command.version() - 1 : current.hrVersion(),
                command.channel() == FieldChannel.TRANSPORT ? command.version() - 1 : current.transportVersion(),
                current.status(), current.updatedAt()
        );
        return applier.decide(lowered, false, command);
    }

    private static boolean conflictingSameVersion(List<ChangeCommand> events) {
        Map<String, ChangeCommand> seen = new HashMap<>();
        for (ChangeCommand command : events) {
            ChangeCommand previous = seen.put(key(command), command);
            if (previous != null && !sameEffect(previous, command)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameEffect(ChangeCommand left, ChangeCommand right) {
        return left.type() == right.type()
                && java.util.Objects.equals(left.fields().name(), right.fields().name())
                && java.util.Objects.equals(left.fields().officeCode(), right.fields().officeCode())
                && java.util.Objects.equals(left.fields().pickupPoint(), right.fields().pickupPoint())
                && java.util.Objects.equals(left.fields().specialAssistance(), right.fields().specialAssistance());
    }

    @Provide
    Arbitrary<List<ChangeCommand>> sequences() {
        Arbitrary<ChangeCommand> event = Arbitraries.oneOf(hrEvents(), transportEvents());
        return event.list().ofMinSize(0).ofMaxSize(24);
    }

    @Provide
    Arbitrary<Long> versions() {
        return Arbitraries.longs().between(1, 12);
    }

    private static Arbitrary<ChangeCommand> hrEvents() {
        return Combinators.combine(
                Arbitraries.of("e1", "e2"),
                Arbitraries.longs().between(1, 12),
                Arbitraries.of(EventType.CREATE, EventType.UPDATE, EventType.DEACTIVATE),
                Arbitraries.of("Ada", "Bea", null),
                Arbitraries.of("BLR", "HYD", null)
        ).as((employeeId, version, type, name, office) -> hr(version, employeeId, type, name, office));
    }

    private static Arbitrary<ChangeCommand> transportEvents() {
        return Combinators.combine(
                Arbitraries.of("e1", "e2"),
                Arbitraries.longs().between(1, 12),
                Arbitraries.of("Gate 4", "Gate 9", null),
                Arbitraries.of("wheelchair", "escort", null)
        ).as((employeeId, version, pickup, assistance) -> new ChangeCommand(
                TENANT,
                employeeId,
                version,
                EventType.UPDATE,
                FieldChannel.TRANSPORT,
                new EmployeeFields(null, null, null, null, null, pickup, assistance, null)
        ));
    }

    private static ChangeCommand hr(long version, String employeeId, EventType type, String name, String office) {
        EmployeeFields fields = type == EventType.DEACTIVATE
                ? EmployeeFields.empty()
                : new EmployeeFields(name, null, null, null, office, "ignored-pickup", "ignored-assistance", null);
        return new ChangeCommand(TENANT, employeeId, version, type, FieldChannel.HR, fields);
    }

    private Run apply(EmployeeChangeApplier decider, List<ChangeCommand> events) {
        return apply(decider, events, new Run(new HashMap<>(), new HashSet<>(), 0));
    }

    private Run apply(EmployeeChangeApplier decider, List<ChangeCommand> events, Run start) {
        Map<String, HrEmployee> rows = new HashMap<>(start.rows());
        Set<String> processed = new HashSet<>(start.processed());
        int applied = 0;
        for (ChangeCommand command : events) {
            Decision decision = decider.decide(rows.get(command.employeeId()), processed.contains(key(command)), command);
            if (decision.kind() != Decision.Kind.APPLY) {
                continue;
            }
            rows.put(command.employeeId(), decision.state());
            processed.add(key(command));
            applied++;
        }
        return new Run(rows, processed, applied);
    }

    private static Map<String, Snapshot> model(List<ChangeCommand> events) {
        Map<String, Snapshot> rows = new HashMap<>();
        Set<String> processed = new HashSet<>();
        for (ChangeCommand command : events) {
            if (!processed.add(key(command))) {
                continue;
            }
            Snapshot current = rows.get(command.employeeId());
            long stored = current == null ? 0 : current.version(command.channel());
            if (current != null && command.version() <= stored) {
                processed.remove(key(command));
                continue;
            }
            rows.put(command.employeeId(), merge(current, command));
        }
        return rows;
    }

    private static Snapshot merge(Snapshot current, ChangeCommand command) {
        if (current == null) {
            if (command.channel() == FieldChannel.TRANSPORT) {
                return new Snapshot(
                        null, null, null, null, null, null,
                        command.fields().pickupPoint(), command.fields().specialAssistance(),
                        0, command.version(), EmployeeStatus.PLACEHOLDER
                );
            }
            boolean deactivate = command.type() == EventType.DEACTIVATE;
            EmployeeStatus status = switch (command.type()) {
                case CREATE -> EmployeeStatus.ACTIVE;
                case DEACTIVATE -> EmployeeStatus.DEACTIVATED;
                case UPDATE -> EmployeeStatus.PLACEHOLDER;
            };
            EmployeeFields fields = command.fields();
            return new Snapshot(
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
                    status
            );
        }
        if (command.channel() == FieldChannel.TRANSPORT) {
            EmployeeFields fields = command.fields();
            return new Snapshot(
                    current.name(), current.email(), current.phone(), current.manager(),
                    current.officeCode(), current.shift(),
                    prefer(fields.pickupPoint(), current.pickupPoint()),
                    prefer(fields.specialAssistance(), current.specialAssistance()),
                    current.hrVersion(), command.version(), current.status()
            );
        }
        EmployeeFields fields = command.fields();
        boolean deactivate = command.type() == EventType.DEACTIVATE;
        boolean create = command.type() == EventType.CREATE;
        return new Snapshot(
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
                deactivate ? EmployeeStatus.DEACTIVATED : (create ? EmployeeStatus.ACTIVE : current.status())
        );
    }

    private static List<ChangeCommand> versionOrder(List<ChangeCommand> events) {
        List<ChangeCommand> ordered = new ArrayList<>(events);
        ordered.sort(Comparator
                .comparing(ChangeCommand::employeeId)
                .thenComparing(command -> command.channel().name())
                .thenComparingLong(ChangeCommand::version));
        return ordered;
    }

    private static long maxVersion(List<ChangeCommand> events, String employeeId, FieldChannel channel) {
        long max = 0;
        for (ChangeCommand command : events) {
            if (command.employeeId().equals(employeeId) && command.channel() == channel) {
                max = Math.max(max, command.version());
            }
        }
        return max;
    }

    private static Set<String> employeeIds(List<ChangeCommand> events) {
        Set<String> ids = new HashSet<>();
        for (ChangeCommand command : events) {
            ids.add(command.employeeId());
        }
        return ids;
    }

    private static String key(ChangeCommand command) {
        return command.employeeId() + "|" + command.channel().name() + "|" + command.version();
    }

    private static String prefer(String incoming, String current) {
        return incoming == null ? current : incoming;
    }

    private record Run(Map<String, HrEmployee> rows, Set<String> processed, int applied) {
        private Map<String, Snapshot> snapshot() {
            Map<String, Snapshot> copy = new HashMap<>();
            for (Map.Entry<String, HrEmployee> entry : rows.entrySet()) {
                copy.put(entry.getKey(), Snapshot.of(entry.getValue()));
            }
            return copy;
        }
    }

    private record Snapshot(
            String name,
            String email,
            String phone,
            String manager,
            String officeCode,
            String shift,
            String pickupPoint,
            String specialAssistance,
            long hrVersion,
            long transportVersion,
            EmployeeStatus status
    ) {
        private static Snapshot of(HrEmployee employee) {
            return new Snapshot(
                    employee.name(), employee.email(), employee.phone(), employee.manager(),
                    employee.officeCode(), employee.shift(), employee.pickupPoint(), employee.specialAssistance(),
                    employee.hrVersion(), employee.transportVersion(), employee.status()
            );
        }

        private long version(FieldChannel channel) {
            return channel == FieldChannel.HR ? hrVersion : transportVersion;
        }
    }
}

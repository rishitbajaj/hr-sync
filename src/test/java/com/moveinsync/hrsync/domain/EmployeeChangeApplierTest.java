package com.moveinsync.hrsync.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeChangeApplierTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private final EmployeeChangeApplier applier = new EmployeeChangeApplier(CLOCK);

    @Test
    void duplicateIsANoOpEvenWhenTheVersionWouldAlsoBeStale() {
        Decision decision = applier.decide(employee(7, 3), true, hrUpdate(7, "Newer"));

        assertThat(decision.kind()).isEqualTo(Decision.Kind.IGNORED);
        assertThat(decision.reason()).isEqualTo(ReasonCode.DUPLICATE);
        assertThat(decision.state()).isNull();
    }

    @Test
    void equalOrLowerVersionIsStale() {
        assertThat(applier.decide(employee(6, 1), false, hrUpdate(6, "Same")).reason())
                .isEqualTo(ReasonCode.STALE_IGNORED);
        assertThat(applier.decide(employee(6, 1), false, hrUpdate(5, "Older")).reason())
                .isEqualTo(ReasonCode.STALE_IGNORED);
    }

    @Test
    void hrChangeNeverCopiesTransportFields() {
        HrEmployee current = HrEmployee.rehydrate(
                null, "acme", "e1", "Ada", "ada@example.com", "+15550000001", "M1", "BLR", "NIGHT",
                "Gate 4", "wheelchair", 1, 9, EmployeeStatus.ACTIVE, Instant.EPOCH
        );
        ChangeCommand command = new ChangeCommand(
                "acme", "e1", 2, EventType.UPDATE, FieldChannel.HR,
                new EmployeeFields("Ada Lovelace", null, null, null, "HYD", "Home Lane", "none", "DAY")
        );

        HrEmployee next = applier.decide(current, false, command).state();

        assertThat(next.name()).isEqualTo("Ada Lovelace");
        assertThat(next.officeCode()).isEqualTo("HYD");
        assertThat(next.shift()).isEqualTo("DAY");
        assertThat(next.pickupPoint()).isEqualTo("Gate 4");
        assertThat(next.specialAssistance()).isEqualTo("wheelchair");
        assertThat(next.hrVersion()).isEqualTo(2);
        assertThat(next.transportVersion()).isEqualTo(9);
    }

    @Test
    void transportChangeNeverCopiesHrFields() {
        HrEmployee current = employee(4, 1);
        ChangeCommand command = new ChangeCommand(
                "acme", "e1", 2, EventType.UPDATE, FieldChannel.TRANSPORT,
                new EmployeeFields("Stolen", "stolen@example.com", "+15550000999", "X", "ZZZ", "Gate 9", "escort", "STOLEN")
        );

        HrEmployee next = applier.decide(current, false, command).state();

        assertThat(next.name()).isEqualTo("Ada");
        assertThat(next.email()).isEqualTo("ada@example.com");
        assertThat(next.officeCode()).isEqualTo("BLR");
        assertThat(next.shift()).isEqualTo("NIGHT");
        assertThat(next.pickupPoint()).isEqualTo("Gate 9");
        assertThat(next.specialAssistance()).isEqualTo("escort");
        assertThat(next.hrVersion()).isEqualTo(4);
        assertThat(next.transportVersion()).isEqualTo(2);
    }

    @Test
    void updateBeforeCreateRecordsATombstoneAtThatVersion() {
        HrEmployee tombstone = applier.decide(null, false, hrUpdate(5, "Later")).state();

        assertThat(tombstone.hrVersion()).isEqualTo(5);
        assertThat(tombstone.name()).isEqualTo("Later");
        assertThat(tombstone.status()).isEqualTo(EmployeeStatus.PLACEHOLDER);
        assertThat(applier.decide(tombstone, false, hrCreate(3, "Early")).reason())
                .isEqualTo(ReasonCode.STALE_IGNORED);

        HrEmployee created = applier.decide(tombstone, false, hrCreate(6, "After")).state();
        assertThat(created.name()).isEqualTo("After");
        assertThat(created.hrVersion()).isEqualTo(6);
        assertThat(created.status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void deactivateBeforeCreateStaysDeactivatedUntilANewerCreate() {
        HrEmployee tombstone = applier.decide(null, false, new ChangeCommand(
                "acme", "e1", 4, EventType.DEACTIVATE, FieldChannel.HR, EmployeeFields.empty()
        )).state();

        assertThat(tombstone.status()).isEqualTo(EmployeeStatus.DEACTIVATED);
        assertThat(tombstone.hrVersion()).isEqualTo(4);
        assertThat(applier.decide(tombstone, false, hrCreate(2, "Early")).reason())
                .isEqualTo(ReasonCode.STALE_IGNORED);

        HrEmployee created = applier.decide(tombstone, false, hrCreate(5, "Joined")).state();
        assertThat(created.status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(created.name()).isEqualTo("Joined");
        assertThat(created.hrVersion()).isEqualTo(5);
    }

    @Test
    void transportFirstSightingStaysHiddenUntilAnHrCreate() {
        HrEmployee placeholder = applier.decide(null, false, new ChangeCommand(
                "acme", "e1", 1, EventType.UPDATE, FieldChannel.TRANSPORT,
                new EmployeeFields(null, null, null, null, null, "Gate 4", "wheelchair", "STOLEN")
        )).state();

        assertThat(placeholder.status()).isEqualTo(EmployeeStatus.PLACEHOLDER);
        assertThat(placeholder.hrVersion()).isZero();
        assertThat(placeholder.pickupPoint()).isEqualTo("Gate 4");
        assertThat(placeholder.shift()).isNull();

        HrEmployee created = applier.decide(placeholder, false, hrCreate(1, "Ada")).state();
        assertThat(created.status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(created.name()).isEqualTo("Ada");
        assertThat(created.pickupPoint()).isEqualTo("Gate 4");
        assertThat(created.specialAssistance()).isEqualTo("wheelchair");
    }

    private static ChangeCommand hrUpdate(long version, String name) {
        return new ChangeCommand(
                "acme", "e1", version, EventType.UPDATE, FieldChannel.HR,
                new EmployeeFields(name, null, null, null, null, null, null, null)
        );
    }

    private static ChangeCommand hrCreate(long version, String name) {
        return new ChangeCommand(
                "acme", "e1", version, EventType.CREATE, FieldChannel.HR,
                new EmployeeFields(name, "ada@example.com", "+15550000001", "M1", "BLR", "should-not-stick", null, null)
        );
    }

    private static HrEmployee employee(long hrVersion, long transportVersion) {
        return HrEmployee.rehydrate(
                null, "acme", "e1", "Ada", "ada@example.com", "+15550000001", "M1", "BLR", "NIGHT",
                "Gate 4", null, hrVersion, transportVersion, EmployeeStatus.ACTIVE, Instant.EPOCH
        );
    }
}

package com.moveinsync.hrsync;

import com.moveinsync.hrsync.api.SyncBatchRequest;
import com.moveinsync.hrsync.api.SyncEventRequest;
import com.moveinsync.hrsync.api.SyncFieldsRequest;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.SyncEvent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class SampleHrBatch {

    static final String TENANT = "acme";
    static final String OFFICE = "BLR";
    static final String UNKNOWN_OFFICE = "NO-SUCH-OFFICE";
    static final String EMAIL_MARKER = "@leak.example";
    static final String PHONE_PREFIX = "+1555";
    static final String BAD_PHONE = "not-a-phone";
    static final String HOME = "12 Secret Home Lane";
    static final String NAME_MARKER = "SecretLegalName";

    private SampleHrBatch() {
    }

    static SyncBatchRequest request() {
        return new SyncBatchRequest(rows().stream()
                .map(row -> new SyncEventRequest(
                        TENANT,
                        row.employeeId(),
                        row.version(),
                        "CREATE",
                        new SyncFieldsRequest(
                                row.name(),
                                row.email(),
                                row.phone(),
                                "M1",
                                row.office(),
                                row.pickup(),
                                null
                        )
                ))
                .toList());
    }

    static List<SyncEvent> domainEvents() {
        return rows().stream()
                .map(row -> new SyncEvent(
                        TENANT,
                        row.employeeId(),
                        row.version(),
                        EventType.CREATE,
                        new EmployeeFields(
                                row.name(),
                                row.email(),
                                row.phone(),
                                "M1",
                                row.office(),
                                row.pickup(),
                                null
                        )
                ))
                .toList();
    }

    static void assertNoPersonalData(String text) {
        assertThat(text)
                .doesNotContain(EMAIL_MARKER)
                .doesNotContain(BAD_PHONE)
                .doesNotContain(PHONE_PREFIX)
                .doesNotContain(HOME)
                .doesNotContain(NAME_MARKER);
    }

    private static List<Row> rows() {
        return List.of(
                new Row("emp-01", 1, NAME_MARKER, "emp-01@leak.example", "+15550000001", OFFICE, HOME),
                new Row("emp-02", 2, "Bea", "emp-02@leak.example", BAD_PHONE, OFFICE, null),
                new Row("emp-03", 3, "Cid", "emp-03@leak.example", "+15550000003", OFFICE, null),
                new Row("emp-04", 4, "Dee", "emp-04@leak.example", "+15550000004", OFFICE, null),
                new Row("emp-05", 5, "Eli", "emp-05@leak.example", "+15550000005", UNKNOWN_OFFICE, null),
                new Row("emp-06", 6, "Fay", "emp-06@leak.example", "+15550000006", OFFICE, null),
                new Row("emp-07", 7, "Gil", "emp-07@leak.example", "+15550000007", OFFICE, null),
                new Row("emp-08", 8, "Hua", "emp-08@leak.example", "+15550000008", OFFICE, null),
                new Row("emp-09", 9, null, "emp-09@leak.example", "+15550000009", OFFICE, null),
                new Row("emp-10", 10, "Jia", "emp-10@leak.example", "+15550000010", OFFICE, null)
        );
    }

    private record Row(
            String employeeId,
            long version,
            String name,
            String email,
            String phone,
            String office,
            String pickup
    ) {
    }
}

package com.moveinsync.hrsync.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportCsvTest {

    @Test
    void parsesQuotedFieldsAndSkipsTheHeader() throws Exception {
        String csv = """
                tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift
                acme,emp-1,3,"Same, Person",a@example.com,+15550000001,M1,BLR,DAY
                acme,emp-2,4,,,,,,
                """;
        List<RosterRow> rows = new ArrayList<>();
        ExportCsv.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), rows::add);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Same, Person");
        assertThat(rows.get(0).sourceVersion()).isEqualTo(3);
        assertThat(rows.get(0).shift()).isEqualTo("DAY");
        assertThat(rows.get(1).employeeId()).isEqualTo("emp-2");
        assertThat(rows.get(1).name()).isNull();
        assertThat(rows.get(1).shift()).isNull();
    }

    @Test
    void rejectsARowWithTheWrongWidth() {
        String csv = "acme,emp-1,1,Ada\n";
        assertThatThrownBy(() -> ExportCsv.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                row -> {
                }
        )).isInstanceOf(ReconciliationException.class);
    }
}

package com.moveinsync.hrsync.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredSecretsTest {

    @Test
    void missingDatasourcePasswordNamesTheVariable() {
        assertThatThrownBy(() -> RequiredSecrets.requireDatasourcePassword("  ", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RequiredSecrets.DATASOURCE_PASSWORD);
    }

    @Test
    void aConnectionSuppliesTheDatasourcePassword() {
        assertThatCode(() -> RequiredSecrets.requireDatasourcePassword("", true))
                .doesNotThrowAnyException();
    }

    @Test
    void missingJwtSecretNamesTheVariable() {
        assertThatThrownBy(() -> RequiredSecrets.requireJwtSecret(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RequiredSecrets.JWT_SECRET);
    }

    @Test
    void aShortJwtSecretIsRejected() {
        assertThatThrownBy(() -> RequiredSecrets.requireJwtSecret("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RequiredSecrets.JWT_SECRET);
    }

    @Test
    void aJwtSecretOf32BytesIsAccepted() {
        assertThatCode(() -> RequiredSecrets.requireJwtSecret("0123456789abcdef0123456789abcdef"))
                .doesNotThrowAnyException();
    }
}

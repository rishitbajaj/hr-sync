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
    void missingOperatorPasswordNamesTheVariable() {
        assertThatThrownBy(() -> RequiredSecrets.requireOperatorPassword(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RequiredSecrets.OPERATOR_PASSWORD);
    }
}

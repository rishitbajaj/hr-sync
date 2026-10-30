package com.moveinsync.hrsync.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapSettingsTest {

    @Test
    void unsetBootstrapIsNotRequested() {
        BootstrapSettings settings = new BootstrapSettings();
        assertThat(settings.anyValueSet()).isFalse();
    }

    @Test
    void aPartialBootstrapNamesTheMissingVariablesAndOmitsThePassword() {
        BootstrapSettings settings = new BootstrapSettings();
        settings.setOperatorUsername("op");
        settings.setOperatorPassword("secret-value");
        assertThatThrownBy(settings::requireComplete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD")
                .hasMessageContaining("HR_SYNC_BOOTSTRAP_ADMIN_TENANT")
                .hasMessageNotContaining("secret-value");
        assertThat(settings.toString()).doesNotContain("secret-value");
    }

    @Test
    void aCompleteBootstrapIsAccepted() {
        BootstrapSettings settings = complete();
        assertThat(settings.anyValueSet()).isTrue();
        assertThatCode(settings::requireComplete).doesNotThrowAnyException();
    }

    static BootstrapSettings complete() {
        BootstrapSettings settings = new BootstrapSettings();
        settings.setOperatorUsername("boot-operator");
        settings.setOperatorPassword("boot-operator-secret");
        settings.setAdminUsername("boot-admin");
        settings.setAdminPassword("boot-admin-secret");
        settings.setAdminTenant("boot-co");
        return settings;
    }
}

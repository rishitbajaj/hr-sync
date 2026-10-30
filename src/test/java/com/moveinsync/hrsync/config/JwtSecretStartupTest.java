package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.HrSyncApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretStartupTest {

    @Test
    void startupFailsWhenTheJwtSecretIsMissing() {
        assertSecretRejected("");
    }

    @Test
    void startupFailsWhenTheJwtSecretIsShorterThan32Bytes() {
        assertSecretRejected("short-secret");
    }

    private static void assertSecretRejected(String secret) {
        Throwable failure = failure(secret);
        boolean found = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (RequiredSecrets.JWT_SECRET.equals(current.getMessage())) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("startup failure should name HR_SYNC_JWT_SECRET, but was %s", failure)
                .isTrue();
    }

    private static Throwable failure(String secret) {
        try {
            SpringApplication.run(
                    HrSyncApplication.class,
                    "--spring.main.web-application-type=none",
                    "--spring.main.banner-mode=off",
                    "--hr-sync.auth.jwt-secret=" + secret
            );
            return new AssertionError("startup succeeded");
        } catch (Throwable thrown) {
            return thrown;
        }
    }
}

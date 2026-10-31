package com.moveinsync.hrsync;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GracefulHttpShutdownTest.HoldHealth.class)
@Testcontainers
class GracefulHttpShutdownTest {

    private static final CountDownLatch ENTERED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final AtomicBoolean HELD = new AtomicBoolean(true);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void inFlightRequestCompletesDuringShutdown() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> response = pool.submit(() ->
                    restTemplate.getForEntity("/health", String.class));
            assertThat(ENTERED.await(15, TimeUnit.SECONDS)).isTrue();
            CountDownLatch stopped = new CountDownLatch(1);
            ((WebServerApplicationContext) context).getWebServer().shutDownGracefully(result -> stopped.countDown());
            assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isFalse();
            HELD.set(false);
            RELEASE.countDown();
            ResponseEntity<String> completed = response.get(15, TimeUnit.SECONDS);
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(completed.getBody()).contains("UP");
            assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            HELD.set(false);
            RELEASE.countDown();
            pool.shutdownNow();
        }
    }

    @TestConfiguration
    static class HoldHealth {

        @Bean
        FilterRegistrationBean<Filter> holdInFlightHealth() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setFilter((request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                if (HELD.get() && "GET".equalsIgnoreCase(http.getMethod()) && "/health".equals(http.getRequestURI())) {
                    ENTERED.countDown();
                    try {
                        if (!RELEASE.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("in-flight health request was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("in-flight health request was interrupted");
                    }
                }
                chain.doFilter(request, response);
            });
            return registration;
        }
    }
}

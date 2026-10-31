package com.moveinsync.hrsync.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.booking.MockBookingService;
import com.moveinsync.hrsync.config.KafkaSettings;
import com.moveinsync.hrsync.config.OutboxSettings;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.metrics.OutboxMetrics;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.DeactivationEventConsumer;
import com.moveinsync.hrsync.service.KafkaEventPublisher;
import com.moveinsync.hrsync.service.KafkaPublishException;
import com.moveinsync.hrsync.service.OutboxPoller;
import com.moveinsync.hrsync.service.OutboxPublishGuard;
import com.moveinsync.hrsync.service.PublishCircuit;
import com.moveinsync.hrsync.service.SyncEventService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KafkaOutboxPublishTest {

    private static final String TOPIC = "hr-sync.employee-changes";
    private static final String OFFICE = "BLR";
    private static final String SECRET_NAME = "Nola Shore";
    private static final String SECRET_EMAIL = "nola.shore@leak.example";
    private static final String SECRET_PHONE = "+15551230000";
    private static final String SECRET_PICKUP = "12 Secret Home Lane";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("hr-sync.publisher", () -> "kafka");
        registry.add("hr-sync.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("hr-sync.kafka.topic", () -> TOPIC);
        registry.add("hr-sync.kafka.partitions", () -> "6");
        registry.add("hr-sync.kafka.max-block", () -> "500ms");
        registry.add("hr-sync.kafka.delivery-timeout", () -> "2s");
        registry.add("hr-sync.kafka.request-timeout", () -> "1s");
        registry.add("hr-sync.outbox.publisher-threads", () -> "2");
        registry.add("hr-sync.outbox.max-attempts", () -> "3");
        registry.add("hr-sync.outbox.retry-base", () -> "0ms");
        registry.add("hr-sync.outbox.retry-max-delay", () -> "0ms");
    }

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private KafkaEventPublisher kafkaPublisher;

    @Autowired
    private DeactivationEventConsumer deactivations;

    @Autowired
    private OutboxPublishGuard publishGuard;

    @Autowired
    private OutboxSettings settings;

    @Autowired
    private KafkaSettings kafkaSettings;

    @Autowired
    private PublishCircuit circuit;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private MockBookingService bookings;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void createTopic() throws Exception {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 6, (short) 1))).all().get(30, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) {
                    throw exception;
                }
            }
        }
    }

    @BeforeEach
    void isolate() {
        unpauseKafka();
        kafkaPublisher.setBeforeAck(null);
        kafkaPublisher.setAfterAck(null);
        publishGuard.setBeforePublish(null);
        bookings.failNext(0);
        kafkaSettings.setCircuitBase(Duration.ZERO);
        kafkaSettings.setCircuitMax(Duration.ZERO);
        circuit.reset();
        drain();
        bookings.reset();
    }

    @AfterEach
    void clearHooks() {
        kafkaPublisher.setBeforeAck(null);
        kafkaPublisher.setAfterAck(null);
        publishGuard.setBeforePublish(null);
        bookings.failNext(0);
        kafkaSettings.setCircuitBase(Duration.ZERO);
        kafkaSettings.setCircuitMax(Duration.ZERO);
        circuit.reset();
        unpauseKafka();
    }

    @Test
    void appliedChangesPublishOneMessageAndNoOpsPublishNone() throws Exception {
        String tenant = "kafka-applied";
        saveOffice(tenant);
        assertThat(apply(create(tenant, "emp-1", 1, "Ada")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(apply(rename(tenant, "emp-1", 3, "Ada Lovelace")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(apply(create(tenant, "emp-1", 1, "Ada")).reasonCode()).isEqualTo(ReasonCode.DUPLICATE);
        assertThat(apply(rename(tenant, "emp-1", 2, "Older")).reasonCode()).isEqualTo(ReasonCode.STALE_IGNORED);
        assertThat(apply(new SyncEvent(
                tenant,
                "emp-bad",
                1L,
                EventType.CREATE,
                new EmployeeFields("Bea", "bea@leak.example", "nope", "M1", OFFICE, null, null, null)
        )).reasonCode()).isEqualTo(ReasonCode.INVALID_PHONE);

        List<Long> ids = outboxIds(tenant, "emp-1");
        assertThat(ids).hasSize(2);
        assertThat(outboxCount(tenant, "emp-bad")).isZero();
        double sent = registry.counter("kafka.published").count();
        drain();
        assertThat(registry.counter("kafka.published").count()).isEqualTo(sent + 2);
        assertThat(registry.timer("kafka.publish").count()).isEqualTo((long) sent + 2);

        List<ConsumerRecord<String, String>> messages = messagesFor(tenant);
        assertThat(messages).hasSize(2);
        assertThat(eventIds(messages)).containsExactlyElementsOf(ids);
        assertThat(messages).allSatisfy(record -> assertThat(record.key()).isEqualTo(tenant + ":emp-1"));
    }

    @Test
    void perEmployeeOrderHoldsAcrossEmployeesAndTwoPollers() throws Exception {
        String tenant = "kafka-order";
        saveOffice(tenant);
        int employees = 8;
        for (int employee = 0; employee < employees; employee++) {
            String employeeId = "emp-" + employee;
            assertThat(apply(create(tenant, employeeId, 1, "Person")).outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(apply(rename(tenant, employeeId, 2, "Person Two")).outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(apply(rename(tenant, employeeId, 3, "Person Three")).outcome()).isEqualTo(Outcome.APPLIED);
        }

        OutboxPoller second = newPoller();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int idle = 0;
            while (pending(tenant) > 0) {
                Future<Integer> first = pool.submit(() -> poller.poll(employees));
                Future<Integer> other = pool.submit(() -> second.poll(employees));
                int delivered = first.get(30, TimeUnit.SECONDS) + other.get(30, TimeUnit.SECONDS);
                if (delivered == 0 && ++idle > 5) {
                    throw new IllegalStateException("pollers made no progress");
                }
                if (delivered > 0) {
                    idle = 0;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, List<Long>> sequences = sequencesByKey(messagesFor(tenant));
        assertThat(sequences).hasSize(employees);
        sequences.forEach((key, sequence) -> {
            assertThat(key).startsWith(tenant + ":");
            assertThat(sequence).hasSize(3);
            assertThat(sequence).isSortedAccordingTo(Comparator.naturalOrder());
            assertThat(sequence).doesNotHaveDuplicates();
        });
    }

    @Test
    void kafkaDownLeavesIngestSuccessfulAndDrainsInOrder() throws Exception {
        String tenant = "kafka-down";
        saveOffice(tenant);
        outboxMetrics.sample();
        double before = registry.get("outbox.pending").gauge().value();
        pauseKafka();
        try {
            ResponseEntity<String> response = postEvents(tenant, "emp-1", 1, "CREATE", fields("Ada"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("APPLIED");
            ResponseEntity<String> renamed = postEvents(tenant, "emp-1", 2, "UPDATE", "{\"name\":\"Ada Two\"}");
            assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(pending(tenant)).isEqualTo(2);
            double failed = registry.counter("kafka.publish.failures").count();
            assertThat(poller.poll()).isZero();
            assertThat(registry.counter("kafka.publish.failures").count()).isGreaterThan(failed);
            assertThat(statuses(tenant, "emp-1")).containsExactly("PENDING", "PENDING");
            outboxMetrics.sample();
            assertThat(registry.get("outbox.pending").gauge().value()).isGreaterThan(before);
        } finally {
            unpauseKafka();
        }

        drain();
        assertThat(pending(tenant)).isZero();
        List<Long> sequences = sequencesByKey(messagesFor(tenant)).get(tenant + ":emp-1");
        EmployeeChangeLog log = new EmployeeChangeLog();
        for (long sequence : sequences) {
            log.accept(sequence, tenant + ":emp-1", sequence);
        }
        assertThat(log.orderViolations()).isZero();
        assertThat(log.unique()).isEqualTo(2);
        assertThat(sequences).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void brokerOutageLongerThanTheAttemptWindowLeavesZeroFailedRows() throws Exception {
        String tenant = "kafka-outage";
        saveOffice(tenant);
        pauseKafka();
        try {
            assertThat(postEvents(tenant, "emp-1", 1, "CREATE", fields("Ada")).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(postEvents(tenant, "emp-1", 2, "UPDATE", "{\"name\":\"Ada Two\"}").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            long pendingBefore = pending(tenant);
            for (int attempt = 0; attempt < settings.getMaxAttempts() + 3; attempt++) {
                assertThat(poller.poll()).isZero();
            }
            assertThat(postEvents(tenant, "emp-2", 1, "CREATE", fields("Bea")).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(pending(tenant)).isGreaterThan(pendingBefore);
            assertThat(failed(tenant)).isZero();
            assertThat(attempts(tenant, "emp-1")).isZero();
            assertThat(attemptsOf(outboxIds(tenant, "emp-2").get(0))).isZero();
            assertThat(statuses(tenant, "emp-1")).containsExactly("PENDING", "PENDING");
        } finally {
            unpauseKafka();
        }

        drain();
        assertThat(failed(tenant)).isZero();
        assertThat(pending(tenant)).isZero();
        EmployeeChangeLog log = new EmployeeChangeLog();
        for (ConsumerRecord<String, String> record : messagesFor(tenant)) {
            long eventId = objectMapper.readTree(record.value()).get("eventId").asLong();
            log.accept(eventId, record.key(), eventId);
        }
        assertThat(log.orderViolations()).isZero();
        assertThat(log.unique()).isEqualTo(3);
    }

    @Test
    void permanentPublishErrorStillEndsFailed() throws Exception {
        String tenant = "kafka-permanent";
        saveOffice(tenant);
        apply(create(tenant, "emp-1", 1, "Ada"));
        long eventId = outboxIds(tenant, "emp-1").get(0);
        kafkaPublisher.setBeforeAck(notice -> {
            throw new org.apache.kafka.common.errors.RecordTooLargeException("too large");
        });
        double before = registry.counter("kafka.publish.permanent").count();

        for (int attempt = 0; attempt < settings.getMaxAttempts(); attempt++) {
            assertThat(poller.poll()).isZero();
        }

        assertThat(statusOf(eventId)).isEqualTo("FAILED");
        assertThat(attemptsOf(eventId)).isEqualTo(settings.getMaxAttempts());
        assertThat(circuit.state()).isEqualTo(PublishCircuit.CLOSED);
        assertThat(registry.counter("kafka.publish.permanent").count()).isGreaterThanOrEqualTo(before + settings.getMaxAttempts());
        assertThat(statusOf(eventId)).isEqualTo("FAILED");
    }

    @Test
    void openCircuitDoesNotThrashTheBroker() throws Exception {
        String tenant = "kafka-circuit";
        saveOffice(tenant);
        apply(create(tenant, "emp-1", 1, "Ada"));
        kafkaSettings.setCircuitBase(Duration.ofSeconds(30));
        kafkaSettings.setCircuitMax(Duration.ofSeconds(30));
        pauseKafka();
        try {
            assertThat(poller.poll()).isZero();
            assertThat(circuit.state()).isEqualTo(PublishCircuit.OPEN);
            double transientFailures = registry.counter("kafka.publish.transient").count();
            double sendFailures = registry.counter("kafka.publish.failures").count();
            for (int extra = 0; extra < 10; extra++) {
                assertThat(poller.poll()).isZero();
            }
            assertThat(registry.counter("kafka.publish.transient").count()).isEqualTo(transientFailures);
            assertThat(registry.counter("kafka.publish.failures").count()).isEqualTo(sendFailures);
            assertThat(attempts(tenant, "emp-1")).isZero();
            assertThat(statusOf(outboxIds(tenant, "emp-1").get(0))).isEqualTo("PENDING");
            assertThat(registry.get("kafka.circuit.state").gauge().value()).isEqualTo(PublishCircuit.OPEN);
        } finally {
            unpauseKafka();
            kafkaSettings.setCircuitBase(Duration.ZERO);
            kafkaSettings.setCircuitMax(Duration.ZERO);
            circuit.reset();
        }
    }

    @Test
    void crashAfterAckRedeliversTheSameEventIdAndTheDemoConsumerDedupes() throws Exception {
        String tenant = "kafka-crash";
        saveOffice(tenant);
        assertThat(apply(create(tenant, "emp-1", 1, "Ada")).outcome()).isEqualTo(Outcome.APPLIED);
        long eventId = outboxIds(tenant, "emp-1").get(0);
        kafkaPublisher.setAfterAck(notice -> {
            throw new Error("crash after ack");
        });

        assertThatThrownBy(() -> poller.poll()).isInstanceOf(Error.class);
        kafkaPublisher.setAfterAck(null);
        assertThat(statusOf(eventId)).isEqualTo("PENDING");
        assertThat(attempts(tenant, "emp-1")).isZero();

        assertThat(poller.poll()).isEqualTo(1);
        assertThat(statusOf(eventId)).isEqualTo("PUBLISHED");
        assertThat(eventIds(messagesFor(tenant))).containsExactly(eventId, eventId);

        EmployeeChangeLog log = DemoConsumer.consume(kafka.getBootstrapServers(), TOPIC, Duration.ofMillis(800));
        assertThat(log.duplicates()).isGreaterThanOrEqualTo(1);
        assertThat(log.orderViolations()).isZero();
        assertThat(log.summary()).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_PHONE).doesNotContain(SECRET_NAME);
    }

    @Test
    void effectFailurePublishesNothing() throws Exception {
        String tenant = "kafka-effect-fail";
        hire(tenant, "emp-1");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", start);
        bookings.failNext(1);
        assertThat(deactivate(tenant, "emp-1", 2).outcome()).isEqualTo(Outcome.APPLIED);
        long eventId = outboxIds(tenant, "emp-1").get(1);

        assertThat(poller.poll()).isZero();
        assertThat(eventIds(messagesFor(tenant))).doesNotContain(eventId);
        assertThat(bookings.isCancelled("later")).isFalse();
        assertThat(bookings.cancelCalls()).isEqualTo(1);
    }

    @Test
    void kafkaFailureAfterEffectDoesNotCancelTwice() throws Exception {
        String tenant = "kafka-effect-retry";
        hire(tenant, "emp-1");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", start);
        assertThat(deactivate(tenant, "emp-1", 2).outcome()).isEqualTo(Outcome.APPLIED);
        long eventId = outboxIds(tenant, "emp-1").get(1);
        pauseKafka();
        try {
            assertThat(poller.poll()).isZero();
        } finally {
            unpauseKafka();
        }

        assertThat(bookings.isCancelled("later")).isTrue();
        assertThat(bookings.cancelCalls()).isEqualTo(1);
        assertThat(effectRows(tenant, "emp-1")).isEqualTo(1);
        assertThat(statusOf(eventId)).isEqualTo("PENDING");

        assertThat(poller.poll()).isEqualTo(1);
        assertThat(bookings.cancelCalls()).isEqualTo(1);
        assertThat(effectRows(tenant, "emp-1")).isEqualTo(1);
        assertThat(eventIds(messagesFor(tenant))).contains(eventId);
    }

    @Test
    void messageOmitsPersonalValuesAndRowStaysPendingUntilAck() throws Exception {
        String tenant = "kafka-leak";
        saveOffice(tenant);
        kafkaPublisher.setBeforeAck(notice -> {
            String status = jdbc.queryForObject("select status from outbox where id = ?", String.class, notice.eventId());
            assertThat(status).isEqualTo("PENDING");
        });
        HttpHeaders headers = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "corr-leak");
        String body = """
                {"events":[{"tenantId":"%s","employeeId":"emp-1","sourceVersion":1,"type":"CREATE","fields":{"name":"%s","email":"%s","phone":"%s","officeCode":"%s","shift":"DAY","pickupPoint":"%s"}}]}
                """.formatted(tenant, SECRET_NAME, SECRET_EMAIL, SECRET_PHONE, OFFICE, SECRET_PICKUP);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/sync/events",
                new HttpEntity<>(body, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        drain();

        List<ConsumerRecord<String, String>> messages = messagesFor(tenant);
        assertThat(messages).hasSize(1);
        ConsumerRecord<String, String> record = messages.get(0);
        assertThat(record.key()).isEqualTo(tenant + ":emp-1");
        JsonNode json = objectMapper.readTree(record.value());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "eventId",
                "tenantId",
                "employeeId",
                "eventType",
                "changedFields",
                "sourceVersion",
                "sequence",
                "occurredAt",
                "schemaVersion"
        );
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("sourceVersion").asLong()).isEqualTo(1);
        assertThat(json.get("sequence").asLong()).isEqualTo(json.get("eventId").asLong());
        assertThat(json.get("changedFields").toString()).doesNotContain(SECRET_NAME).doesNotContain(SECRET_EMAIL);
        String wire = record.value() + " " + headerText(record);
        assertThat(wire)
                .doesNotContain(SECRET_NAME)
                .doesNotContain(SECRET_EMAIL)
                .doesNotContain(SECRET_PHONE)
                .doesNotContain(SECRET_PICKUP)
                .doesNotContain("Nola")
                .doesNotContain("Shore")
                .doesNotContain("leak.example");
        assertThat(header(record, KafkaEventPublisher.CORRELATION_HEADER)).isEqualTo("corr-leak");
    }

    @Test
    void manyEmployeesDrainWithinBoundAndKeepPerEmployeeOrder() throws Exception {
        String tenant = "kafka-throughput";
        saveOffice(tenant);
        int employees = 40;
        int versions = 10;
        for (int employee = 0; employee < employees; employee++) {
            String employeeId = "emp-" + employee;
            assertThat(apply(create(tenant, employeeId, 1, "Person")).outcome()).isEqualTo(Outcome.APPLIED);
            for (int version = 2; version <= versions; version++) {
                assertThat(apply(rename(tenant, employeeId, version, "Person " + version)).outcome())
                        .isEqualTo(Outcome.APPLIED);
            }
        }
        Map<String, Long> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
        kafkaPublisher.setBeforeAck(notice -> {
            Long already = inFlight.putIfAbsent(notice.tenantId() + ":" + notice.aggregateId(), notice.eventId());
            assertThat(already).as("two rows of one employee in one round").isNull();
        });
        kafkaPublisher.setAfterAck(notice -> inFlight.remove(notice.tenantId() + ":" + notice.aggregateId()));
        long started = System.nanoTime();
        assertThat(poller.poll()).isGreaterThanOrEqualTo(20);
        Map<String, List<Long>> firstRound = sequencesByKey(messagesFor(tenant));
        assertThat(firstRound.size()).isGreaterThanOrEqualTo(10);
        firstRound.values().forEach(sequence -> assertThat(sequence).hasSize(1));
        drain();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        kafkaPublisher.setBeforeAck(null);
        kafkaPublisher.setAfterAck(null);
        assertThat(elapsedMs).as("drain ms").isLessThan(20_000);
        assertThat(pending(tenant)).isZero();
        Map<String, List<Long>> sequences = sequencesByKey(messagesFor(tenant));
        assertThat(sequences).hasSize(employees);
        sequences.forEach((key, sequence) -> {
            assertThat(sequence).hasSize(versions);
            assertThat(sequence).isSortedAccordingTo(Comparator.naturalOrder());
            assertThat(sequence).doesNotHaveDuplicates();
        });
    }

    @Test
    void failureMidRoundSkipsOnlyThatEmployee() throws Exception {
        String tenant = "kafka-mid-round";
        saveOffice(tenant);
        apply(create(tenant, "emp-a", 1, "Ada"));
        apply(rename(tenant, "emp-a", 2, "Ada Two"));
        apply(rename(tenant, "emp-a", 3, "Ada Three"));
        apply(create(tenant, "emp-b", 1, "Bea"));
        apply(rename(tenant, "emp-b", 2, "Bea Two"));
        apply(rename(tenant, "emp-b", 3, "Bea Three"));
        List<Long> stopped = outboxIds(tenant, "emp-a");
        List<Long> other = outboxIds(tenant, "emp-b");
        kafkaPublisher.setBeforeAck(notice -> {
            if (notice.eventId() == stopped.get(1)) {
                throw new KafkaPublishException("down");
            }
        });

        assertThat(poller.poll()).isEqualTo(4);
        assertThat(eventIds(messagesFor(tenant))).doesNotContain(stopped.get(2));
        assertThat(statusOf(stopped.get(2))).isEqualTo("PENDING");
        assertThat(attemptsOf(stopped.get(1))).isEqualTo(1);
        assertThat(statusOf(stopped.get(0))).isEqualTo("PUBLISHED");
        assertThat(statuses(tenant, "emp-b")).containsExactly("PUBLISHED", "PUBLISHED", "PUBLISHED");
        assertThat(eventIds(messagesFor(tenant))).contains(other.get(0), other.get(1), other.get(2));
    }

    @Test
    void crashMidRoundRedeliversWithoutLossOrReordering() throws Exception {
        String tenant = "kafka-mid-crash";
        saveOffice(tenant);
        apply(create(tenant, "emp-a", 1, "Ada"));
        apply(rename(tenant, "emp-a", 2, "Ada Two"));
        apply(create(tenant, "emp-b", 1, "Bea"));
        apply(rename(tenant, "emp-b", 2, "Bea Two"));
        List<Long> first = outboxIds(tenant, "emp-a");
        List<Long> second = outboxIds(tenant, "emp-b");
        kafkaPublisher.setAfterAck(notice -> {
            if (notice.eventId() == first.get(0)) {
                throw new Error("crash mid-round");
            }
        });

        assertThatThrownBy(() -> poller.poll()).isInstanceOf(Error.class);
        kafkaPublisher.setAfterAck(null);
        assertThat(statuses(tenant, "emp-a")).containsExactly("PENDING", "PENDING");
        assertThat(attemptsOf(first.get(0))).isZero();
        assertThat(eventIds(messagesFor(tenant))).doesNotContain(first.get(1));

        drain();
        assertThat(statuses(tenant, "emp-a")).containsExactly("PUBLISHED", "PUBLISHED");
        assertThat(statuses(tenant, "emp-b")).containsExactly("PUBLISHED", "PUBLISHED");
        EmployeeChangeLog log = new EmployeeChangeLog();
        for (ConsumerRecord<String, String> record : messagesFor(tenant)) {
            long eventId = objectMapper.readTree(record.value()).get("eventId").asLong();
            log.accept(eventId, record.key(), eventId);
        }
        assertThat(log.orderViolations()).isZero();
        assertThat(log.unique()).isEqualTo(4);
        assertThat(log.duplicates()).isGreaterThanOrEqualTo(1);
        Map<String, List<Long>> sequences = sequencesByKey(messagesFor(tenant));
        assertThat(deduped(sequences.get(tenant + ":emp-a"))).containsExactlyElementsOf(first);
        assertThat(deduped(sequences.get(tenant + ":emp-b"))).containsExactlyElementsOf(second);
    }

    private static List<Long> deduped(List<Long> ids) {
        List<Long> unique = new ArrayList<>();
        for (Long id : ids) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(id)) {
                unique.add(id);
            }
        }
        return unique;
    }

    @Test
    void laterEventIsNotSentAfterAnEarlierFailure() throws Exception {
        String tenant = "kafka-stop";
        saveOffice(tenant);
        apply(create(tenant, "emp-1", 1, "Ada"));
        apply(rename(tenant, "emp-1", 2, "Ada Two"));
        List<Long> ids = outboxIds(tenant, "emp-1");
        kafkaPublisher.setBeforeAck(notice -> {
            if (notice.eventId() == ids.get(0)) {
                throw new KafkaPublishException("down");
            }
        });

        assertThat(poller.poll()).isZero();
        assertThat(eventIds(messagesFor(tenant))).doesNotContain(ids.get(1));
        assertThat(statusOf(ids.get(1))).isEqualTo("PENDING");
        assertThat(attempts(tenant, "emp-1")).isEqualTo(1);
    }

    @Test
    void messageKeyIncludesTheTenant() throws Exception {
        saveOffice("tenant-a");
        saveOffice("tenant-b");
        apply(create("tenant-a", "emp-1", 1, "Ada"));
        apply(create("tenant-b", "emp-1", 1, "Bea"));
        drain();

        List<String> keys = messagesFor("tenant-a", "tenant-b").stream().map(ConsumerRecord::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("tenant-a:emp-1", "tenant-b:emp-1");
    }

    @Test
    void retryFailedReplayAndDiscardStillWork() throws Exception {
        String tenant = "kafka-replay";
        hire(tenant, "emp-1");
        bookings.failNext(2);
        assertThat(deactivate(tenant, "emp-1", 2).outcome()).isEqualTo(Outcome.APPLIED);
        long eventId = outboxIds(tenant, "emp-1").get(1);
        assertThat(poller.poll()).isZero();
        assertThat(poller.poll()).isZero();
        assertThat(attemptsOf(eventId)).isEqualTo(2);
        assertThat(statusOf(eventId)).isEqualTo("PENDING");
        assertThat(eventIds(messagesFor(tenant))).doesNotContain(eventId);

        bookings.failNext(0);
        assertThat(poller.poll()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(eventId)).isEqualTo("PUBLISHED");
        assertThat(eventIds(messagesFor(tenant))).containsOnlyOnce(eventId);

        String blocked = "kafka-failed";
        hire(blocked, "emp-1");
        bookings.failNext(Integer.MAX_VALUE);
        deactivate(blocked, "emp-1", 2);
        long failedId = outboxIds(blocked, "emp-1").get(1);
        for (int attempt = 0; attempt < settings.getMaxAttempts(); attempt++) {
            assertThat(poller.poll()).isZero();
        }
        assertThat(statusOf(failedId)).isEqualTo("FAILED");
        assertThat(eventIds(messagesFor(blocked))).doesNotContain(failedId);

        HttpHeaders operator = TestAccounts.operator(jdbc, passwordEncoder, restTemplate, objectMapper);
        ResponseEntity<String> replayed = restTemplate.exchange(
                "/outbox/" + failedId + "/replay?tenantId=" + blocked,
                HttpMethod.POST,
                new HttpEntity<>(operator),
                String.class
        );
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        bookings.failNext(0);
        drain();
        assertThat(statusOf(failedId)).isEqualTo("PUBLISHED");
        assertThat(eventIds(messagesFor(blocked))).containsOnlyOnce(failedId);

        String discardedTenant = "kafka-discard";
        hire(discardedTenant, "emp-1");
        bookings.failNext(Integer.MAX_VALUE);
        deactivate(discardedTenant, "emp-1", 2);
        long discardId = outboxIds(discardedTenant, "emp-1").get(1);
        for (int attempt = 0; attempt < settings.getMaxAttempts(); attempt++) {
            assertThat(poller.poll()).isZero();
        }
        HttpHeaders discardHeaders = TestAccounts.operator(jdbc, passwordEncoder, restTemplate, objectMapper);
        discardHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> discarded = restTemplate.postForEntity(
                "/outbox/" + discardId + "/discard?tenantId=" + discardedTenant,
                new HttpEntity<>("{\"reason\":\"poison event\"}", discardHeaders),
                String.class
        );
        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusOf(discardId)).isEqualTo("DISCARDED");
        apply(rename(discardedTenant, "emp-1", 3, "Still Here"));
        drain();
        assertThat(eventIds(messagesFor(discardedTenant))).doesNotContain(discardId);
        assertThat(jdbc.queryForObject(
                "select reason from outbox_discard where event_id = ?",
                String.class,
                discardId
        )).isEqualTo("poison event");
    }

    private void hire(String tenant, String employeeId) {
        saveOffice(tenant);
        assertThat(apply(create(tenant, employeeId, 1, "Ada")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(poller.poll()).isGreaterThanOrEqualTo(1);
    }

    private IngestResult deactivate(String tenant, String employeeId, long version) {
        return apply(new SyncEvent(tenant, employeeId, version, EventType.DEACTIVATE, EmployeeFields.empty()));
    }

    private IngestResult apply(SyncEvent event) {
        return syncEvents.ingest(List.of(event)).get(0);
    }

    private ResponseEntity<String> postEvents(
            String tenant,
            String employeeId,
            long version,
            String type,
            String fields
    ) {
        HttpHeaders headers = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"events":[{"tenantId":"%s","employeeId":"%s","sourceVersion":%d,"type":"%s","fields":%s}]}
                """.formatted(tenant, employeeId, version, type, fields);
        return restTemplate.postForEntity("/sync/events", new HttpEntity<>(body, headers), String.class);
    }

    private static String fields(String name) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + name.toLowerCase()
                + "@leak.example\",\"phone\":\"+15550001111\",\"officeCode\":\"" + OFFICE + "\",\"shift\":\"DAY\"}";
    }

    private void drain() {
        int idle = 0;
        while (pendingAll() > 0) {
            int delivered = poller.poll();
            if (delivered == 0) {
                if (++idle > 5) {
                    throw new IllegalStateException("outbox did not drain");
                }
            } else {
                idle = 0;
            }
        }
    }

    private OutboxPoller newPoller() {
        return new OutboxPoller(
                jdbc,
                transactionManager,
                new com.moveinsync.hrsync.service.CompositeEventPublisher(deactivations, kafkaPublisher),
                publishGuard,
                settings,
                objectMapper,
                clock,
                new com.moveinsync.hrsync.metrics.SyncMeters(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                circuit
        );
    }

    private void saveOffice(String tenant) {
        if (!offices.existsByTenantIdAndCode(tenant, OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), tenant, OFFICE));
        }
    }

    private static SyncEvent create(String tenant, String employeeId, long version, String name) {
        return new SyncEvent(
                tenant,
                employeeId,
                version,
                EventType.CREATE,
                new EmployeeFields(name, employeeId + "@leak.example", "+15550000001", "M1", OFFICE, null, null, "DAY")
        );
    }

    private static SyncEvent rename(String tenant, String employeeId, long version, String name) {
        return new SyncEvent(
                tenant,
                employeeId,
                version,
                EventType.UPDATE,
                new EmployeeFields(name, null, null, null, null, null, null, null)
        );
    }

    private void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpauseKafka() {
        Boolean paused = kafka.getDockerClient()
                .inspectContainerCmd(kafka.getContainerId())
                .exec()
                .getState()
                .getPaused();
        if (Boolean.TRUE.equals(paused)) {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    private List<ConsumerRecord<String, String>> messagesFor(String... tenants) throws Exception {
        Set<String> wanted = Set.of(tenants);
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        for (ConsumerRecord<String, String> record : readTopic()) {
            JsonNode body = objectMapper.readTree(record.value());
            if (wanted.contains(body.path("tenantId").asText())) {
                matched.add(record);
            }
        }
        matched.sort(Comparator
                .comparingInt(ConsumerRecord<String, String>::partition)
                .thenComparingLong(ConsumerRecord::offset));
        return matched;
    }

    private List<ConsumerRecord<String, String>> readTopic() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> all = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var batch = consumer.poll(Duration.ofMillis(200));
                batch.forEach(all::add);
                if (caughtUp(consumer, partitions)) {
                    break;
                }
            }
            return all;
        }
    }

    private static boolean caughtUp(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions) {
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
        for (TopicPartition partition : partitions) {
            long end = ends.getOrDefault(partition, 0L);
            if (end > 0 && consumer.position(partition) < end) {
                return false;
            }
        }
        return true;
    }

    private List<Long> eventIds(List<ConsumerRecord<String, String>> records) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            ids.add(objectMapper.readTree(record.value()).get("eventId").asLong());
        }
        return ids;
    }

    private Map<String, List<Long>> sequencesByKey(List<ConsumerRecord<String, String>> records) throws Exception {
        Map<String, List<Long>> sequences = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            long sequence = objectMapper.readTree(record.value()).get("sequence").asLong();
            sequences.computeIfAbsent(record.key(), key -> new ArrayList<>()).add(sequence);
        }
        return sequences;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String headerText(ConsumerRecord<String, String> record) {
        StringBuilder text = new StringBuilder();
        for (Header header : record.headers()) {
            text.append(header.key()).append('=').append(new String(header.value(), StandardCharsets.UTF_8)).append(' ');
        }
        return text.toString();
    }

    private long pendingAll() {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where status = 'PENDING'",
                Long.class
        );
        return count == null ? 0 : count;
    }

    private long failed(String tenant) {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where tenant_id = ? and status = 'FAILED'",
                Long.class,
                tenant
        );
        return count == null ? 0 : count;
    }

    private long pending(String tenant) {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where status = 'PENDING' and tenant_id = ?",
                Long.class,
                tenant
        );
        return count == null ? 0 : count;
    }

    private long outboxCount(String tenant, String employeeId) {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where tenant_id = ? and aggregate_id = ?",
                Long.class,
                tenant,
                employeeId
        );
        return count == null ? 0 : count;
    }

    private List<Long> outboxIds(String tenant, String employeeId) {
        return jdbc.queryForList(
                "select id from outbox where tenant_id = ? and aggregate_id = ? order by id",
                Long.class,
                tenant,
                employeeId
        );
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from outbox where id = ?", String.class, id);
    }

    private int attempts(String tenant, String employeeId) {
        Integer attempts = jdbc.queryForObject(
                """
                        select attempts from outbox
                        where tenant_id = ? and aggregate_id = ?
                        order by id
                        limit 1
                        """,
                Integer.class,
                tenant,
                employeeId
        );
        return attempts == null ? 0 : attempts;
    }

    private int attemptsOf(long id) {
        Integer attempts = jdbc.queryForObject("select attempts from outbox where id = ?", Integer.class, id);
        return attempts == null ? 0 : attempts;
    }

    private List<String> statuses(String tenant, String employeeId) {
        return jdbc.queryForList(
                "select status from outbox where tenant_id = ? and aggregate_id = ? order by id",
                String.class,
                tenant,
                employeeId
        );
    }

    private long effectRows(String tenant, String employeeId) {
        Long count = jdbc.queryForObject(
                "select count(*) from deactivation_effect where tenant_id = ? and employee_id = ?",
                Long.class,
                tenant,
                employeeId
        );
        return count == null ? 0 : count;
    }
}

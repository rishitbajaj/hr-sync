package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.metrics.SyncMeters;
import com.moveinsync.hrsync.observability.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class SyncEventService {

    private static final Logger log = LoggerFactory.getLogger(SyncEventService.class);

    private final BatchIngestor ingestor;
    private final SyncMeters meters;

    public SyncEventService(BatchIngestor ingestor, SyncMeters meters) {
        this.ingestor = ingestor;
        this.meters = meters;
    }

    public List<IngestResult> ingest(List<SyncEvent> events) {
        return ingest(events, FieldChannel.HR);
    }

    public List<IngestResult> ingest(List<SyncEvent> events, FieldChannel channel) {
        long started = System.nanoTime();
        List<IngestResult> results = ingestor.ingest(events, channel);
        meters.recordIngestDuration(Duration.ofNanos(System.nanoTime() - started));
        results.forEach(result -> {
            meters.recordIngest(result, channel);
            logResult(result);
        });
        if (results.stream().anyMatch(result -> result.outcome() == Outcome.RETRYABLE_ERROR)) {
            throw new IngestUnavailableException(results);
        }
        return results;
    }

    private void logResult(IngestResult result) {
        try (MdcScope ignored = MdcScope.open("tenantId", result.tenantId()).and("employeeId", result.employeeId())) {
            logResultInScope(result);
        }
    }

    private void logResultInScope(IngestResult result) {
        if (result.outcome() == Outcome.APPLIED) {
            log.info(
                    "applied tenantId={} employeeId={} sourceVersion={}",
                    result.tenantId(),
                    result.employeeId(),
                    result.sourceVersion()
            );
            return;
        }
        if (result.outcome() == Outcome.IGNORED) {
            log.info(
                    "ignored tenantId={} employeeId={} sourceVersion={} reason={}",
                    result.tenantId(),
                    result.employeeId(),
                    result.sourceVersion(),
                    result.reasonCode()
            );
            return;
        }
        if (result.outcome() == Outcome.RETRYABLE_ERROR) {
            log.warn(
                    "retryable tenantId={} employeeId={} sourceVersion={} reason={}",
                    result.tenantId(),
                    result.employeeId(),
                    result.sourceVersion(),
                    result.reasonCode()
            );
            return;
        }
        log.warn(
                "rejected tenantId={} employeeId={} sourceVersion={} reason={}",
                result.tenantId(),
                result.employeeId(),
                result.sourceVersion(),
                result.reasonCode()
        );
    }
}

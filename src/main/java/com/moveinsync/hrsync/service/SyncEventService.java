package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.SyncEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SyncEventService {

    private static final Logger log = LoggerFactory.getLogger(SyncEventService.class);

    private final BatchIngestor ingestor;

    public SyncEventService(BatchIngestor ingestor) {
        this.ingestor = ingestor;
    }

    public List<IngestResult> ingest(List<SyncEvent> events) {
        return ingest(events, FieldChannel.HR);
    }

    public List<IngestResult> ingest(List<SyncEvent> events, FieldChannel channel) {
        List<IngestResult> results = ingestor.ingest(events, channel);
        results.forEach(this::logResult);
        return results;
    }

    private void logResult(IngestResult result) {
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
        log.warn(
                "rejected tenantId={} employeeId={} sourceVersion={} reason={}",
                result.tenantId(),
                result.employeeId(),
                result.sourceVersion(),
                result.reasonCode()
        );
    }
}

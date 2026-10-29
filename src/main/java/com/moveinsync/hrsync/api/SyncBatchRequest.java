package com.moveinsync.hrsync.api;

import java.util.List;

public record SyncBatchRequest(List<SyncEventRequest> events) {
}

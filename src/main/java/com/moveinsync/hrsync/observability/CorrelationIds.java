package com.moveinsync.hrsync.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";

    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");

    private CorrelationIds() {
    }

    public static String resolve(String inbound) {
        if (inbound != null && VALID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}

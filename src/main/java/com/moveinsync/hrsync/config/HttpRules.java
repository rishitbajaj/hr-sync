package com.moveinsync.hrsync.config;

import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * Every mapped endpoint must appear here. A mapped path with no rule is denied.
 * A path that no controller maps is not found. A path mapped for another method is not allowed.
 */
public final class HttpRules {

    public enum Access {
        OPEN,
        ADMIN,
        OPERATOR,
        ADMIN_OR_OPERATOR,
        EMPLOYEE_ADMIN_OR_OPERATOR
    }

    public record Rule(HttpMethod method, String pattern, Access access) {
    }

    public static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.GET, "/health", Access.OPEN),
            new Rule(HttpMethod.POST, "/auth/token", Access.OPEN),
            new Rule(null, "/error", Access.OPEN),
            new Rule(null, "/actuator", Access.OPERATOR),
            new Rule(null, "/actuator/**", Access.OPERATOR),
            new Rule(HttpMethod.POST, "/sync/events", Access.ADMIN),
            new Rule(HttpMethod.POST, "/sync/transport-events", Access.ADMIN),
            new Rule(HttpMethod.GET, "/employees/{id}", Access.EMPLOYEE_ADMIN_OR_OPERATOR),
            new Rule(HttpMethod.POST, "/reconciliation/runs/{id}/approve", Access.OPERATOR),
            new Rule(HttpMethod.GET, "/reconciliation/runs/{id}", Access.ADMIN_OR_OPERATOR),
            new Rule(HttpMethod.POST, "/reconciliation/runs", Access.ADMIN),
            new Rule(HttpMethod.GET, "/outbox/failed", Access.OPERATOR),
            new Rule(HttpMethod.POST, "/outbox/{id}/replay", Access.OPERATOR),
            new Rule(HttpMethod.POST, "/outbox/{id}/discard", Access.OPERATOR),
            new Rule(null, "/demo/**", Access.OPEN)
    );

    private HttpRules() {
    }

    public static boolean covers(String method, String pattern) {
        String normalized = stripTrailingSlash(pattern);
        for (Rule rule : RULES) {
            if (rule.method() != null && (method == null || !rule.method().name().equals(method))) {
                continue;
            }
            if (samePattern(rule.pattern(), normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePattern(String rule, String actual) {
        if (rule.equals(actual)) {
            return true;
        }
        if (rule.endsWith("/**")) {
            String prefix = rule.substring(0, rule.length() - 3);
            return actual.equals(prefix) || actual.startsWith(prefix + "/");
        }
        return false;
    }

    private static String stripTrailingSlash(String pattern) {
        if (pattern.length() > 1 && pattern.endsWith("/")) {
            return pattern.substring(0, pattern.length() - 1);
        }
        return pattern;
    }
}

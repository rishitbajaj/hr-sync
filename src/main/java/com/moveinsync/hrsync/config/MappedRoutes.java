package com.moveinsync.hrsync.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

@Component
public class MappedRoutes {

    public static final String UNKNOWN = "hrsync.unknown-route";

    public static final String METHOD_NOT_ALLOWED = "hrsync.method-not-allowed";

    public enum Match {
        METHOD,
        OTHER_METHOD,
        NONE
    }

    private final ObjectProvider<RequestMappingInfoHandlerMapping> mappings;

    public MappedRoutes(ObjectProvider<RequestMappingInfoHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    public Match classify(HttpServletRequest request) {
        String path = path(request);
        String method = request.getMethod();
        boolean otherMethod = false;
        for (RequestMappingInfoHandlerMapping mapping : mappings) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                if (!pathMatches(info, path)) {
                    continue;
                }
                var methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty() || methods.stream().anyMatch(candidate -> candidate.name().equalsIgnoreCase(method))) {
                    return Match.METHOD;
                }
                otherMethod = true;
            }
        }
        return otherMethod ? Match.OTHER_METHOD : Match.NONE;
    }

    private static boolean pathMatches(RequestMappingInfo info, String path) {
        if (info.getPathPatternsCondition() == null) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
            if (pattern.matches(container)) {
                return true;
            }
        }
        return false;
    }

    private static String path(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty() && path.startsWith(context)) {
                path = path.substring(context.length());
            }
        }
        return path == null || path.isEmpty() ? "/" : path;
    }
}

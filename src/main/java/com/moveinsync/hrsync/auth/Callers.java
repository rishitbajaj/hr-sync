package com.moveinsync.hrsync.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class Callers {

    private Callers() {
    }

    public static AuthenticatedCaller require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedCaller caller) {
            return caller;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    /**
     * An admin or an employee always uses the tenant in the token.
     * A request tenant that is present and different is rejected and must not be applied.
     * An operator must name the tenant on the request.
     */
    public static String tenant(String requested) {
        AuthenticatedCaller caller = require();
        if (caller.role() == UserRole.OPERATOR) {
            if (requested == null || requested.isBlank()) {
                throw new TenantRequiredException();
            }
            return requested;
        }
        if (requested != null && !requested.isBlank() && !requested.equals(caller.tenantId())) {
            throw new TenantMismatchException();
        }
        return caller.tenantId();
    }
}

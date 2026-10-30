package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.AuthenticatedCaller;
import com.moveinsync.hrsync.auth.UserRole;
import com.moveinsync.hrsync.repo.EmployeeEntity;
import com.moveinsync.hrsync.repo.EmployeeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmployeeController {

    private final EmployeeRepository employees;

    public EmployeeController(EmployeeRepository employees) {
        this.employees = employees;
    }

    @GetMapping("/employees/{id}")
    public EmployeeResponse get(
            @PathVariable String id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        AuthenticatedCaller caller = caller();
        String tenant = tenant(caller, tenantId);
        if (caller.role() == UserRole.EMPLOYEE && !id.equals(caller.employeeId())) {
            throw new EmployeeNotFoundException();
        }
        EmployeeEntity employee = employees.findVisibleByTenantIdAndEmployeeId(tenant, id)
                .orElseThrow(EmployeeNotFoundException::new);
        var roster = employee.toDomain();
        return new EmployeeResponse(
                roster.tenantId(),
                roster.employeeId(),
                employee.getStatus().name(),
                roster.officeCode(),
                roster.shift()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied() {
        return ResponseEntity.status(403).body(new ApiError("FORBIDDEN", "Forbidden"));
    }

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<ApiError> missing() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Employee not found"));
    }

    @ExceptionHandler(TenantRequiredException.class)
    public ResponseEntity<ApiError> tenantRequired() {
        return ResponseEntity.status(400).body(new ApiError("INVALID_REQUEST", "A tenant is required"));
    }

    private static String tenant(AuthenticatedCaller caller, String requested) {
        if (caller.role() == UserRole.OPERATOR) {
            if (requested == null || requested.isBlank()) {
                throw new TenantRequiredException();
            }
            return requested;
        }
        if (requested != null && !requested.isBlank() && !requested.equals(caller.tenantId())) {
            throw new AccessDeniedException("tenant");
        }
        return caller.tenantId();
    }

    private static AuthenticatedCaller caller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedCaller caller) {
            return caller;
        }
        throw new AccessDeniedException("caller");
    }

    public record EmployeeResponse(
            String tenantId,
            String employeeId,
            String status,
            String officeCode,
            String shift
    ) {
    }

    static class EmployeeNotFoundException extends RuntimeException {
    }

    static class TenantRequiredException extends RuntimeException {
    }
}

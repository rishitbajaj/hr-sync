package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.auth.UserRole;
import com.moveinsync.hrsync.cache.CachedEmployee;
import com.moveinsync.hrsync.cache.EmployeeReads;
import com.moveinsync.hrsync.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmployeeController {

    private final EmployeeReads employees;

    public EmployeeController(EmployeeReads employees) {
        this.employees = employees;
    }

    @Operation(
            summary = "Read one employee",
            description = "Employee (self only), admin, or operator. An operator or admin passes tenantId when the token has no tenant. Placeholders are not returned. The body is tenantId, employeeId, status, officeCode, and shift.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "The visible employee.")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "The tenant does not match the token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown id, another employee's id, or a placeholder.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/employees/{id}")
    public EmployeeResponse get(
            @PathVariable String id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        var caller = Callers.require();
        String tenant = Callers.tenant(tenantId);
        if (caller.role() == UserRole.EMPLOYEE && !id.equals(caller.employeeId())) {
            throw new EmployeeNotFoundException();
        }
        CachedEmployee employee = employees.get(tenant, id).orElseThrow(EmployeeNotFoundException::new);
        return new EmployeeResponse(
                employee.tenantId(),
                employee.employeeId(),
                employee.status(),
                employee.officeCode(),
                employee.shift()
        );
    }

    public record EmployeeResponse(
            @Schema(example = "tenant-a") String tenantId,
            @Schema(example = "employee-1") String employeeId,
            @Schema(example = "ACTIVE") String status,
            @Schema(example = "OFFICE-1") String officeCode,
            @Schema(example = "DAY") String shift
    ) {
    }

    static class EmployeeNotFoundException extends RuntimeException {
    }
}

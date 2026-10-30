package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.auth.UserRole;
import com.moveinsync.hrsync.repo.EmployeeEntity;
import com.moveinsync.hrsync.repo.EmployeeRepository;
import org.springframework.http.ResponseEntity;
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
        var caller = Callers.require();
        String tenant = Callers.tenant(tenantId);
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

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<ApiError> missing() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Employee not found"));
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
}

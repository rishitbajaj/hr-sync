package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.auth.UserRole;
import com.moveinsync.hrsync.cache.CachedEmployee;
import com.moveinsync.hrsync.cache.EmployeeReads;
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

# Security

An endpoint that is not in this table is denied. OPERATOR is platform staff. An operator may read or change data in more than one tenant only where a rule says so, and only when that request names the tenant.

| Method | Path | Who |
| --- | --- | --- |
| GET | `/health` | anyone |
| POST | `/auth/token` | anyone |
| any | `/error` | anyone; this is the error page and it returns no tenant data |
| any | `/actuator` and `/actuator/**` | OPERATOR |
| POST | `/sync/events` | ADMIN of that tenant |
| POST | `/sync/transport-events` | ADMIN of that tenant |
| GET | `/employees/{id}` | EMPLOYEE, only for their own `employee_id`. ADMIN, for any employee in their tenant. OPERATOR, only when the request names the tenant. Placeholder rows are never returned. |
| POST | `/reconciliation/runs` | ADMIN of that tenant |
| GET | `/reconciliation/runs/{id}` | ADMIN of that tenant, or OPERATOR |
| POST | `/reconciliation/runs/{id}/approve` | OPERATOR |
| GET | `/outbox/failed` | OPERATOR |
| POST | `/outbox/{id}/replay` | OPERATOR |
| POST | `/outbox/{id}/discard` | OPERATOR |
| any | `/demo/**` | only when the demo profile is on; the routes are not registered otherwise |

A caller with no token receives 401. A token with the wrong role receives 403.

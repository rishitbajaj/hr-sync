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

## Tenant

An ADMIN or an EMPLOYEE always acts as the tenant in the token. A `tenantId` in the body, the query string, or a reconciliation file that is present and different from that tenant is rejected with 403, and the request writes nothing. A read for a row that belongs to another tenant is 404, so the response does not reveal that the row exists.

An OPERATOR must pass `tenantId` on employee reads, reconciliation reads and approvals, and outbox reads, replays, and discards. The named tenant is the only one that request can see. A row in a different tenant is 404.

## Queries that are not limited to one tenant

These statements do not take a tenant argument. Each one stays inside the process and does not return another tenant's data to a caller.

| Statement | Why it spans tenants |
| --- | --- |
| Outbox poller claim | One poller drains pending rows for every tenant. The claim does not leave the process. Later updates on a claimed row include that row's tenant, and the publisher is given only that row. |
| Retention deletes | Deletes old published outbox rows and old processed events. It returns nothing. |
| Interrupted reconciliation sweep | At startup, every `RUNNING` reconciliation is marked `FAILED` so a crash cannot leave a run open. It returns nothing. |
| Login by username | Usernames are unique, and `POST /auth/token` has no tenant. The token then carries the user's tenant. |
| Demo booking board, reset, occupancy, and cancellation lookup by id | Helpers for the demo profile and tests. Cancel of future bookings filters by tenant. Demo routes are not registered unless the demo profile is on. |
| `JpaRepository` `findAll` and `findById` | Inherited from Spring Data and not called by a service. The queries the services use include the tenant. |

## Token

`POST /auth/token` takes a JSON body `{"username","password"}` and returns `{"token","expiresInSeconds"}`. The password is stored as a BCrypt hash. The token is a JWT with header `{"alg":"HS256","typ":"JWT"}` and these claims:

| Claim | Meaning |
| --- | --- |
| `sub` | username |
| `tenant` | tenant id |
| `role` | `EMPLOYEE`, `ADMIN`, or `OPERATOR` |
| `employee_id` | present only when the role is `EMPLOYEE` |
| `exp` | expiry, as Unix seconds |

The signing key is `HR_SYNC_JWT_SECRET`. It must be at least 32 bytes. There is no default. Startup fails with `HR_SYNC_JWT_SECRET is required and must be at least 32 bytes. Set it in the environment. There is no default.` The lifetime is `hr-sync.auth.token-ttl`, default 15 minutes.

A request with a token is rejected with 401 when the signature is wrong, the token is expired, the algorithm is not `HS256` (including `alg=none`), or the role is not one of the three above. The same 401 is used for an unknown username, a wrong password, and a locked username. Five failures lock that username for 15 minutes (`hr-sync.auth.max-login-failures` and `hr-sync.auth.login-lockout`). The counters are in memory and disappear when the process stops. Tokens and passwords are not written to logs or error bodies.

The `demo` profile inserts three users, all with password `demo-password`: `operator` (tenant `platform`), `admin` (tenant `demo-co`), and `employee` (tenant `demo-co`, employee `emp-demo`). No other profile seeds users.

## Trade-offs

HS256 uses one shared secret. Anyone who can check a token can also sign one. An asymmetric key (RS256 or ES256) would let another service check tokens with only a public key. This process is the only checker, so the secret stays here. Changing the secret signs everyone out, because old tokens no longer verify.

There is no refresh token. When a token expires, the client signs in again. A refresh token would be a second secret with a longer life.

There is no revocation list. A stolen token works until `exp`. A denylist would be a lookup on every request and would need storage shared by every instance. Fifteen minutes is the window.

There is no way to cancel one token before it expires. Locking the username blocks the next login. It does not cancel a token that was already issued.

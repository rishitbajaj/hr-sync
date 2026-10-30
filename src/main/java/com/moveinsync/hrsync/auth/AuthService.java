package com.moveinsync.hrsync.auth;

import com.moveinsync.hrsync.metrics.SyncMeters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService tokens;
    private final LoginLockout lockout;
    private final SyncMeters meters;
    private final String dummyHash;

    public AuthService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            JwtService tokens,
            LoginLockout lockout,
            SyncMeters meters
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.lockout = lockout;
        this.meters = meters;
        this.dummyHash = passwordEncoder.encode("not-a-password");
    }

    public String login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            meters.loginFailed();
            throw new AuthenticationRejectedException();
        }
        String normalized = username.trim();
        if (lockout.isLocked(normalized)) {
            meters.loginFailed();
            throw new AuthenticationRejectedException();
        }
        StoredUser user = find(normalized);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            if (user == null) {
                passwordEncoder.matches(password, dummyHash);
            }
            if (lockout.recordFailure(normalized)) {
                meters.lockout();
            }
            meters.loginFailed();
            throw new AuthenticationRejectedException();
        }
        lockout.clear(normalized);
        return tokens.issue(new AuthenticatedCaller(user.username(), user.tenantId(), user.role(), user.employeeId()));
    }

    private StoredUser find(String username) {
        List<StoredUser> rows = jdbc.query(
                """
                        select username, tenant_id, password_hash, role, employee_id
                        from users
                        where username = ?
                        """,
                (rs, row) -> new StoredUser(
                        rs.getString("username"),
                        rs.getString("tenant_id"),
                        rs.getString("password_hash"),
                        UserRole.parse(rs.getString("role")),
                        rs.getString("employee_id")
                ),
                username
        );
        if (rows.isEmpty() || rows.get(0).role() == null) {
            return null;
        }
        return rows.get(0);
    }

    private record StoredUser(
            String username,
            String tenantId,
            String passwordHash,
            UserRole role,
            String employeeId
    ) {
    }
}

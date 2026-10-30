package com.moveinsync.hrsync.auth;

import com.moveinsync.hrsync.config.AuthSettings;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory lockout per username. Five failed logins lock the username for fifteen minutes
 * (both values come from {@code hr-sync.auth}). The counters disappear when the process stops.
 * A locked username, an unknown username, and a wrong password all get the same rejection,
 * so the response does not say which one happened.
 */
@Component
public class LoginLockout {

    private final Clock clock;
    private final AuthSettings settings;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginLockout(Clock clock, AuthSettings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public boolean isLocked(String username) {
        Attempt current = attempts.get(username);
        return current != null && current.lockedUntil != null && current.lockedUntil.isAfter(clock.instant());
    }

    public boolean recordFailure(String username) {
        Instant now = clock.instant();
        int maxFailures = settings.getMaxLoginFailures();
        boolean[] started = {false};
        attempts.compute(username, (key, current) -> {
            boolean expired = current != null && current.lockedUntil != null && !current.lockedUntil.isAfter(now);
            int count = current == null || expired ? 1 : current.count + 1;
            Instant lockedUntil = count >= maxFailures ? now.plus(settings.getLoginLockout()) : null;
            boolean alreadyLocked = current != null && current.lockedUntil != null && !expired;
            started[0] = lockedUntil != null && !alreadyLocked;
            return new Attempt(count, lockedUntil);
        });
        return started[0];
    }

    public void clear(String username) {
        attempts.remove(username);
    }

    private record Attempt(int count, Instant lockedUntil) {
    }
}

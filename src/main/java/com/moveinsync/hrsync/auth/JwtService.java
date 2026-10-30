package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.hrsync.config.AuthSettings;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@Service
public class JwtService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AuthSettings settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] header;

    public JwtService(AuthSettings settings, ObjectMapper objectMapper, Clock clock) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.header = ENCODER.encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    }

    public String issue(AuthenticatedCaller caller) {
        return issue(caller, clock.instant().plus(settings.getTokenTtl()));
    }

    public String issue(AuthenticatedCaller caller, Instant expiresAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sub", caller.username());
        payload.put("tenant", caller.tenantId());
        payload.put("role", caller.role().name());
        if (caller.role() == UserRole.EMPLOYEE) {
            payload.put("employee_id", caller.employeeId());
        }
        payload.put("exp", expiresAt.getEpochSecond());
        String encodedPayload = new String(ENCODER.encode(payload.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String signingInput = new String(header, StandardCharsets.UTF_8) + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
    }

    public AuthenticatedCaller parse(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtRejectedException();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new JwtRejectedException();
        }
        JsonNode headerNode = readJson(parts[0]);
        if (headerNode == null || !headerNode.path("alg").asText().equals("HS256")) {
            throw new JwtRejectedException();
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(signingInput).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new JwtRejectedException();
        }
        JsonNode payload = readJson(parts[1]);
        if (payload == null || !payload.isObject()) {
            throw new JwtRejectedException();
        }
        long expiry = payload.path("exp").asLong(0);
        if (expiry <= 0 || !Instant.ofEpochSecond(expiry).isAfter(clock.instant())) {
            throw new JwtRejectedException();
        }
        String username = text(payload, "sub");
        String tenant = text(payload, "tenant");
        UserRole role = UserRole.parse(text(payload, "role"));
        if (username == null || tenant == null || role == null) {
            throw new JwtRejectedException();
        }
        String employeeId = text(payload, "employee_id");
        if (role == UserRole.EMPLOYEE && employeeId == null) {
            throw new JwtRejectedException();
        }
        if (role != UserRole.EMPLOYEE) {
            employeeId = null;
        }
        return new AuthenticatedCaller(username, tenant, role, employeeId);
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new String(ENCODER.encode(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign a token");
        }
    }

    private JsonNode readJson(String encoded) {
        try {
            return objectMapper.readTree(DECODER.decode(encoded));
        } catch (Exception exception) {
            return null;
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}

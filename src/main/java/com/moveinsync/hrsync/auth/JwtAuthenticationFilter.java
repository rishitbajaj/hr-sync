package com.moveinsync.hrsync.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.moveinsync.hrsync.api.ApiError;
import com.moveinsync.hrsync.metrics.SyncMeters;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String UNAUTHORIZED_MESSAGE = "Invalid token";

    private final JwtService tokens;
    private final SyncMeters meters;

    public JwtAuthenticationFilter(JwtService tokens, SyncMeters meters) {
        this.tokens = tokens;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!header.startsWith("Bearer ")) {
            reject(response);
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        AuthenticatedCaller caller;
        try {
            caller = tokens.parse(token);
        } catch (RuntimeException exception) {
            reject(response);
            return;
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                caller,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + caller.role().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        meters.tokenRejected();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiError.json("UNAUTHORIZED", UNAUTHORIZED_MESSAGE));
    }
}

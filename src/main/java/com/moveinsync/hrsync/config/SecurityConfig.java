package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.auth.JwtAuthenticationFilter;
import com.moveinsync.hrsync.auth.JwtService;
import com.moveinsync.hrsync.metrics.SyncMeters;
import com.moveinsync.hrsync.observability.CorrelationIds;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    static final String UNAUTHORIZED = "Authentication required";
    static final String FORBIDDEN = "Forbidden";

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtService tokens, SyncMeters meters) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    for (HttpRules.Rule rule : HttpRules.RULES) {
                        var authorized = rule.method() == null
                                ? auth.requestMatchers(rule.pattern())
                                : auth.requestMatchers(rule.method(), rule.pattern());
                        switch (rule.access()) {
                            case OPEN -> authorized.permitAll();
                            case ADMIN -> authorized.hasRole("ADMIN");
                            case OPERATOR -> authorized.hasRole("OPERATOR");
                            case ADMIN_OR_OPERATOR -> authorized.hasAnyRole("ADMIN", "OPERATOR");
                            case EMPLOYEE_ADMIN_OR_OPERATOR -> authorized.hasAnyRole("EMPLOYEE", "ADMIN", "OPERATOR");
                        }
                    }
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                write(response, 401, "UNAUTHORIZED", UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, 403, "FORBIDDEN", FORBIDDEN)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(new JwtAuthenticationFilter(tokens, meters), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void write(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body(code, message));
    }

    private static String body(String code, String message) {
        String correlationId = CorrelationIds.current();
        if (correlationId == null) {
            return "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}";
        }
        return "{\"error\":\"" + code + "\",\"message\":\"" + message + "\",\"correlationId\":\"" + correlationId + "\"}";
    }
}

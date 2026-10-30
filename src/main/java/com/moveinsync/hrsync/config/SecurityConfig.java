package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.api.ApiError;
import com.moveinsync.hrsync.auth.JwtAuthenticationFilter;
import com.moveinsync.hrsync.auth.JwtService;
import com.moveinsync.hrsync.metrics.SyncMeters;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authorization.AuthorizationDecision;
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
    SecurityFilterChain security(HttpSecurity http, JwtService tokens, SyncMeters meters, MappedRoutes routes)
            throws Exception {
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
                    auth.anyRequest().access((authentication, context) -> {
                        HttpServletRequest request = context.getRequest();
                        switch (routes.classify(request)) {
                            case OTHER_METHOD -> request.setAttribute(MappedRoutes.METHOD_NOT_ALLOWED, Boolean.TRUE);
                            case NONE -> request.setAttribute(MappedRoutes.UNKNOWN, Boolean.TRUE);
                            case METHOD -> {
                            }
                        }
                        return new AuthorizationDecision(false);
                    });
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                deny(request, response, 401, "UNAUTHORIZED", UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                deny(request, response, 403, "FORBIDDEN", FORBIDDEN)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(new JwtAuthenticationFilter(tokens, meters), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws java.io.IOException {
        if (Boolean.TRUE.equals(request.getAttribute(MappedRoutes.UNKNOWN))) {
            write(response, 404, "NOT_FOUND", "Not found");
            return;
        }
        if (Boolean.TRUE.equals(request.getAttribute(MappedRoutes.METHOD_NOT_ALLOWED))) {
            write(response, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
            return;
        }
        write(response, status, code, message);
    }

    private static void write(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiError.json(code, message));
    }
}

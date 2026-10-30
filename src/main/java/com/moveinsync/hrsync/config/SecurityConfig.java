package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.auth.JwtAuthenticationFilter;
import com.moveinsync.hrsync.auth.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    static final String UNAUTHORIZED = "{\"error\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}";
    static final String FORBIDDEN = "{\"error\":\"FORBIDDEN\",\"message\":\"Forbidden\"}";

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtService tokens) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/reconciliation/runs/*/approve").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/outbox/failed").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/outbox/*/replay").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/outbox/*/discard").hasRole("OPERATOR")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> write(response, 401, UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> write(response, 403, FORBIDDEN)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(new JwtAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void write(HttpServletResponse response, int status, String body) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}

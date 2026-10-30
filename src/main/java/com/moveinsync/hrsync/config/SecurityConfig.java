package com.moveinsync.hrsync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/reconciliation/runs/*/approve").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/outbox/failed").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/outbox/*/replay").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/outbox/*/discard").hasRole("OPERATOR")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService operators(ReconciliationSettings settings, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.builder()
                .username("operator")
                .password(encoder.encode(settings.getOperatorPassword()))
                .roles("OPERATOR")
                .build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

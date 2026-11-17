package com.example.baseplate.security.security;

import com.example.baseplate.security.config.SecurityProperties;
import com.example.baseplate.security.web.JwtAuthenticationFilter;
import com.example.baseplate.security.web.MustChangePasswordFilter;
import com.example.baseplate.security.web.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 无状态 API 安全链：关表单登录、关 CSRF（纯 Bearer + 无 Cookie 会话）、禁用 session。
 * Security 7 只支持 Lambda DSL，别抄 and() 的旧写法。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain baseplateSecurityFilterChain(HttpSecurity http,
                                                            JwtAuthenticationFilter jwtFilter,
                                                            SecurityProperties properties) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    properties.getPermitPaths().forEach(path -> auth.requestMatchers(path).permitAll());
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new TraceIdFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new MustChangePasswordFilter(properties.getMustChangeAllowedPaths()),
                        JwtAuthenticationFilter.class);
        return http.build();
    }
}

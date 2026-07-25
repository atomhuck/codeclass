package ru.tutor.codeclass.config;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.tutor.codeclass.domain.Role;
import ru.tutor.codeclass.security.*;
import ru.tutor.codeclass.service.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountService accounts,
                                             LoginAttemptService attempts, InvitationService invitations,
                                             @Value("${app.account-gate-enabled:true}") boolean accountGateEnabled) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/vendor/**", "/login", "/register",
                        "/forgot-password", "/reset-password",
                        "/oauth2/**", "/login/oauth2/**", "/auth/vk/**",
                        "/invite/**",
                        "/legal/**", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/teacher/**").hasRole("TEACHER")
                .requestMatchers("/student/**").hasRole("STUDENT")
                .anyRequest().authenticated())
            .addFilterBefore(new LoginRateLimitFilter(attempts), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new AccountStateFilter(accounts, accountGateEnabled), UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form.loginPage("/login")
                .successHandler((request, response, authentication) -> {
                    attempts.loginSucceeded(authentication.getName());
                    var user = accounts.requireByUsername(authentication.getName());
                    if (accounts.needsLegalAcceptance(user) || user.getEmail() == null) {
                        response.sendRedirect("/account/consent");
                    } else if (!user.isEmailVerified()) {
                        response.sendRedirect("/verify-email/pending");
                    } else {
                        String invitation = invitations.pendingPath(request.getSession(false)).orElse(null);
                        boolean teacher = authentication.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_" + Role.TEACHER));
                        response.sendRedirect(invitation != null ? invitation : (teacher ? "/teacher" : "/student"));
                    }
                })
                .failureHandler((request, response, exception) -> {
                    attempts.loginFailed(request.getParameter("username"), request.getRemoteAddr());
                    response.sendRedirect("/login?error");
                }).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll());
        return http.build();
    }
}

package ru.tutor.codeclass.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import ru.tutor.codeclass.domain.Role;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/vendor/**", "/login", "/register", "/error",
                        "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/teacher/**").hasRole("TEACHER")
                .requestMatchers("/student/**").hasRole("STUDENT")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").successHandler((request, response, authentication) -> {
                boolean teacher = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + Role.TEACHER));
                response.sendRedirect(teacher ? "/teacher" : "/student");
            }).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll());
        return http.build();
    }
}

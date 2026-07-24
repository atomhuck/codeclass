package ru.tutor.codeclass.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ru.tutor.codeclass.domain.User;
import java.util.Collection;
import java.util.List;

public record CodeClassPrincipal(Long userId, String username, String password, String role,
                                 boolean enabled, long authVersion) implements UserDetails {
    public static CodeClassPrincipal from(User user) {
        return new CodeClassPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getRole().name(), user.isEnabled(), user.getAuthVersion());
    }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
}

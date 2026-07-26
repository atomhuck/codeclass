package ru.repethelper.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ru.repethelper.domain.User;
import java.util.Collection;
import java.util.List;

public record RepetHelperPrincipal(Long userId, String username, String password, String role,
                                 boolean enabled, long authVersion) implements UserDetails {
    public static RepetHelperPrincipal from(User user) {
        return new RepetHelperPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(),
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

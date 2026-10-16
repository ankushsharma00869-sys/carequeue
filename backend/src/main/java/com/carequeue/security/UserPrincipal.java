package com.carequeue.security;

import com.carequeue.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
@AllArgsConstructor
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String password;
    private final String fullName;
    private final Collection<? extends GrantedAuthority> authorities;
    private final Long doctorId;
    private final Long patientId;

    public static UserPrincipal create(User user, Long doctorId, Long patientId) {
        List<GrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );

        if (!user.isWalkIn()) {
            return new UserPrincipal(
                user.getId(), user.getEmail(), user.getPassword(), user.getFullName(), authorities, doctorId, patientId);
        }
        // Walk-in accounts exist only so reception can book for them. Spring refuses login for disabled users,
        // so they can never obtain a token, even with the correct password.
        return new UserPrincipal(
            user.getId(), user.getEmail(), user.getPassword(), user.getFullName(), authorities, doctorId, patientId) {
            @Override
            public boolean isEnabled() {
                return false;
            }
        };
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

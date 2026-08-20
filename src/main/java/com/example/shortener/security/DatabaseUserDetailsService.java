package com.example.shortener.security;

import com.example.shortener.domain.AppUser;
import com.example.shortener.repository.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                // The message is deliberately identical whether the user is missing or the
                // password is wrong. Distinguishing them turns the login endpoint into a
                // free username-enumeration oracle.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.roleList().toArray(String[]::new))
                .disabled(!user.isEnabled())
                .build();
    }
}

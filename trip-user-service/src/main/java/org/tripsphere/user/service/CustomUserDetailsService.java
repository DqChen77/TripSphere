package org.tripsphere.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.tripsphere.user.repository.UserEntityRepository;
import org.tripsphere.user.security.CustomUserDetails;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserEntityRepository userEntityRepository;

    /**
     * Loads the user by email and wraps the full {@link org.tripsphere.user.model.UserEntity} in a
     * {@link CustomUserDetails}. This allows callers to retrieve the entity directly from the
     * {@link org.springframework.security.core.Authentication#getPrincipal() Authentication
     * principal} after a successful authentication, avoiding a redundant second database query.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userEntityRepository
                .findByEmail(email)
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}

package org.tripsphere.user.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.tripsphere.user.exception.UnauthenticatedException;

/**
 * Utility for extracting the current authenticated user from the security context. Provides a
 * clean, reusable way for gRPC services to access authentication details.
 */
@Component
public class AuthContext {

    /**
     * Get the current authenticated JWT token from the security context.
     *
     * @return the current JwtAuthenticationToken
     * @throws UnauthenticatedException if no valid JWT authentication is present
     */
    public JwtAuthenticationToken getAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw UnauthenticatedException.authenticationRequired();
        }

        return jwtAuth;
    }

    /**
     * Get the email of the current authenticated user.
     *
     * @return the email of the authenticated user
     * @throws UnauthenticatedException if no valid authentication is present or email is empty
     */
    public String getEmail() {
        String email = getAuthentication().getEmail();

        if (email == null || email.isBlank()) {
            throw UnauthenticatedException.authenticationRequired();
        }

        return email;
    }
}

package org.tripsphere.user.security;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.tripsphere.user.util.JwtUtil;

/**
 * gRPC authentication reader that extracts JWT token from metadata and creates Authentication
 * object for Spring Security.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtGrpcAuthenticationReader implements GrpcAuthenticationReader {

    private static final String AUTHORIZATION_HEADER = "authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    public Authentication readAuthentication(ServerCall<?, ?> call, Metadata headers)
            throws AuthenticationException {
        String token = extractToken(headers);

        if (token == null) {
            return null; // No authentication provided, allow anonymous access
        }

        try {
            String username = jwtUtil.extractUsername(token);
            if (username == null || !jwtUtil.validateToken(token, username)) {
                // Invalid token, return null to allow anonymous access
                // This allows public endpoints like Login and Register to work
                return null;
            }

            List<String> roles = jwtUtil.extractRoles(token);
            return new JwtAuthenticationToken(token, username, roles);
        } catch (Exception e) {
            // Token validation failed (e.g., expired, malformed, wrong signature)
            // Return null to allow anonymous access for public endpoints like Login and Register
            log.debug("JWT token validation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Extract JWT token from metadata authorization header. */
    private String extractToken(Metadata headers) {
        String authHeader =
                headers.get(
                        Metadata.Key.of(AUTHORIZATION_HEADER, Metadata.ASCII_STRING_MARSHALLER));

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        return null;
    }
}

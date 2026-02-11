package org.tripsphere.user.exception;

import io.grpc.Status;

/** Exception thrown when authentication fails or is required. */
public class UnauthenticatedException extends BusinessException {

    public UnauthenticatedException(String message) {
        super(message, Status.Code.UNAUTHENTICATED);
    }

    public static UnauthenticatedException invalidCredentials() {
        return new UnauthenticatedException("Invalid username or password");
    }

    public static UnauthenticatedException authenticationRequired() {
        return new UnauthenticatedException("Authentication required");
    }
}

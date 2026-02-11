package org.tripsphere.itinerary.exception;

import io.grpc.Status;

/** Exception thrown when authentication is required but not provided. */
public class UnauthenticatedException extends BusinessException {

    public UnauthenticatedException(String message) {
        super(message, Status.Code.UNAUTHENTICATED);
    }

    public static UnauthenticatedException authenticationRequired() {
        return new UnauthenticatedException("Authentication required");
    }
}

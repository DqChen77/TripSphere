package org.tripsphere.itinerary.exception;

import io.grpc.Status;

/** Exception thrown when a user does not have permission to perform an action. */
public class PermissionDeniedException extends BusinessException {

    public PermissionDeniedException(String message) {
        super(message, Status.Code.PERMISSION_DENIED);
    }

    public static PermissionDeniedException notOwner() {
        return new PermissionDeniedException("You don't have permission to access this resource");
    }

    public static PermissionDeniedException unauthenticated() {
        return new PermissionDeniedException("Authentication required");
    }
}

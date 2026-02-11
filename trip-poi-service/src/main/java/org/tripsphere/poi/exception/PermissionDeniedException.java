package org.tripsphere.poi.exception;

import io.grpc.Status;

/** Exception thrown when a user does not have permission to perform an action. */
public class PermissionDeniedException extends BusinessException {

    public PermissionDeniedException(String message) {
        super(message, Status.Code.PERMISSION_DENIED);
    }

    public static PermissionDeniedException adminRequired() {
        return new PermissionDeniedException("Admin privileges required");
    }
}

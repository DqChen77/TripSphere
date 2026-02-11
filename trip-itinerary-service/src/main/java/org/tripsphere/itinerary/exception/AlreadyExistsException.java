package org.tripsphere.itinerary.exception;

import io.grpc.Status;

/** Exception thrown when attempting to create a resource that already exists. */
public class AlreadyExistsException extends BusinessException {

    public AlreadyExistsException(String message) {
        super(message, Status.Code.ALREADY_EXISTS);
    }

    public AlreadyExistsException(String resourceType, String id) {
        super(resourceType + " with ID '" + id + "' already exists", Status.Code.ALREADY_EXISTS);
    }
}

package org.tripsphere.itinerary.exception;

import io.grpc.Status;

/** Exception thrown when an invalid argument is provided. */
public class InvalidArgumentException extends BusinessException {

    public InvalidArgumentException(String message) {
        super(message, Status.Code.INVALID_ARGUMENT);
    }

    /** Create an exception for a required field that is missing. */
    public static InvalidArgumentException required(String fieldName) {
        return new InvalidArgumentException("Field '" + fieldName + "' is required");
    }

    /** Create an exception for an invalid field value. */
    public static InvalidArgumentException invalid(String fieldName, String reason) {
        return new InvalidArgumentException("Field '" + fieldName + "' is invalid: " + reason);
    }
}

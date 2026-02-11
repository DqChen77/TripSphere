package org.tripsphere.attraction.api.grpc.advice;

import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.tripsphere.attraction.exception.BusinessException;

/**
 * Global exception handler for gRPC services. Converts exceptions to appropriate gRPC Status
 * responses.
 */
@Slf4j
@GrpcAdvice
public class GrpcExceptionAdvice {

    /**
     * Handle all business exceptions. These are expected exceptions with predefined gRPC status
     * codes.
     */
    @GrpcExceptionHandler(BusinessException.class)
    public Status handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return e.toGrpcStatus();
    }

    /** Handle illegal argument exceptions (from validation). */
    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    /** Handle null pointer exceptions. */
    @GrpcExceptionHandler(NullPointerException.class)
    public Status handleNullPointerException(NullPointerException e) {
        log.error("Null pointer exception", e);
        return Status.INTERNAL.withDescription("Internal error: null reference");
    }

    /**
     * Handle all other unexpected exceptions. These are logged as errors and returned as INTERNAL
     * status.
     */
    @GrpcExceptionHandler(Exception.class)
    public Status handleException(Exception e) {
        log.error("Unexpected exception in gRPC service", e);
        return Status.INTERNAL.withDescription("Internal server error: " + e.getMessage());
    }
}

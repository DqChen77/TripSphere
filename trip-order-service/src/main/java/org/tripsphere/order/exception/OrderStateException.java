package org.tripsphere.order.exception;

import io.grpc.Status;

public class OrderStateException extends BusinessException {

    public OrderStateException(String message) {
        super(message, Status.Code.FAILED_PRECONDITION);
    }

    public OrderStateException(String orderId, String currentStatus, String requiredStatus) {
        super(
                String.format(
                        "Order '%s' is in status '%s', expected '%s'",
                        orderId, currentStatus, requiredStatus),
                Status.Code.FAILED_PRECONDITION);
    }
}

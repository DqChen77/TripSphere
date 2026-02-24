package org.tripsphere.inventory.exception;

import io.grpc.Status;

public class InsufficientInventoryException extends BusinessException {

    public InsufficientInventoryException(String skuId, String date, int requested, int available) {
        super(
                String.format(
                        "Insufficient inventory for SKU '%s' on %s: requested=%d, available=%d",
                        skuId, date, requested, available),
                Status.Code.FAILED_PRECONDITION);
    }
}

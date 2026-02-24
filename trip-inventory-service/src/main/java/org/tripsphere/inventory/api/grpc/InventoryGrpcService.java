package org.tripsphere.inventory.api.grpc;

import io.grpc.stub.StreamObserver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.tripsphere.inventory.exception.InvalidArgumentException;
import org.tripsphere.inventory.mapper.InventoryMapper;
import org.tripsphere.inventory.service.InventoryService;
import org.tripsphere.inventory.v1.*;

@GrpcService
@RequiredArgsConstructor
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final InventoryService inventoryService;
    private final InventoryMapper inventoryMapper = InventoryMapper.INSTANCE;

    // ===================================================================
    // Calendar Inventory Management
    // ===================================================================

    @Override
    public void setDailyInventory(
            SetDailyInventoryRequest request,
            StreamObserver<SetDailyInventoryResponse> responseObserver) {
        if (request.getSkuId().isEmpty()) {
            throw InvalidArgumentException.required("sku_id");
        }
        if (!request.hasDate()) {
            throw InvalidArgumentException.required("date");
        }

        LocalDate date = inventoryMapper.protoToLocalDate(request.getDate());
        String currency = "CNY";
        long units = 0;
        int nanos = 0;
        if (request.hasPrice()) {
            currency =
                    request.getPrice().getCurrency().isEmpty()
                            ? "CNY"
                            : request.getPrice().getCurrency();
            units = request.getPrice().getUnits();
            nanos = request.getPrice().getNanos();
        }

        DailyInventory result =
                inventoryService.setDailyInventory(
                        request.getSkuId(),
                        date,
                        request.getTotalQuantity(),
                        currency,
                        units,
                        nanos);

        responseObserver.onNext(
                SetDailyInventoryResponse.newBuilder().setInventory(result).build());
        responseObserver.onCompleted();
    }

    @Override
    public void batchSetDailyInventory(
            BatchSetDailyInventoryRequest request,
            StreamObserver<BatchSetDailyInventoryResponse> responseObserver) {
        if (request.getItemsList().isEmpty()) {
            throw new InvalidArgumentException("Items list is empty");
        }

        List<InventoryService.SetDailyInventoryParams> params = new ArrayList<>();
        for (SetDailyInventoryRequest item : request.getItemsList()) {
            LocalDate date = inventoryMapper.protoToLocalDate(item.getDate());
            String currency = "CNY";
            long units = 0;
            int nanos = 0;
            if (item.hasPrice()) {
                currency =
                        item.getPrice().getCurrency().isEmpty()
                                ? "CNY"
                                : item.getPrice().getCurrency();
                units = item.getPrice().getUnits();
                nanos = item.getPrice().getNanos();
            }
            params.add(
                    new InventoryService.SetDailyInventoryParams(
                            item.getSkuId(),
                            date,
                            item.getTotalQuantity(),
                            currency,
                            units,
                            nanos));
        }

        List<DailyInventory> results = inventoryService.batchSetDailyInventory(params);

        responseObserver.onNext(
                BatchSetDailyInventoryResponse.newBuilder().addAllInventories(results).build());
        responseObserver.onCompleted();
    }

    // ===================================================================
    // Query
    // ===================================================================

    @Override
    public void getDailyInventory(
            GetDailyInventoryRequest request,
            StreamObserver<GetDailyInventoryResponse> responseObserver) {
        if (request.getSkuId().isEmpty()) {
            throw InvalidArgumentException.required("sku_id");
        }
        if (!request.hasDate()) {
            throw InvalidArgumentException.required("date");
        }

        LocalDate date = inventoryMapper.protoToLocalDate(request.getDate());
        DailyInventory result = inventoryService.getDailyInventory(request.getSkuId(), date);

        responseObserver.onNext(
                GetDailyInventoryResponse.newBuilder().setInventory(result).build());
        responseObserver.onCompleted();
    }

    @Override
    public void queryInventoryCalendar(
            QueryInventoryCalendarRequest request,
            StreamObserver<QueryInventoryCalendarResponse> responseObserver) {
        if (request.getSkuId().isEmpty()) {
            throw InvalidArgumentException.required("sku_id");
        }
        if (!request.hasStartDate() || !request.hasEndDate()) {
            throw new InvalidArgumentException("Both start_date and end_date are required");
        }

        LocalDate start = inventoryMapper.protoToLocalDate(request.getStartDate());
        LocalDate end = inventoryMapper.protoToLocalDate(request.getEndDate());

        List<DailyInventory> entries =
                inventoryService.queryInventoryCalendar(request.getSkuId(), start, end);

        responseObserver.onNext(
                QueryInventoryCalendarResponse.newBuilder().addAllEntries(entries).build());
        responseObserver.onCompleted();
    }

    @Override
    public void checkAvailability(
            CheckAvailabilityRequest request,
            StreamObserver<CheckAvailabilityResponse> responseObserver) {
        if (request.getSkuId().isEmpty()) {
            throw InvalidArgumentException.required("sku_id");
        }
        if (!request.hasDate()) {
            throw InvalidArgumentException.required("date");
        }

        LocalDate date = inventoryMapper.protoToLocalDate(request.getDate());
        InventoryService.CheckAvailabilityResult result =
                inventoryService.checkAvailability(request.getSkuId(), date, request.getQuantity());

        CheckAvailabilityResponse.Builder responseBuilder =
                CheckAvailabilityResponse.newBuilder()
                        .setAvailable(result.available())
                        .setAvailableQuantity(result.availableQuantity())
                        .setPrice(
                                org.tripsphere.common.v1.Money.newBuilder()
                                        .setCurrency(result.priceCurrency())
                                        .setUnits(result.priceUnits())
                                        .setNanos(result.priceNanos())
                                        .build());

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    // ===================================================================
    // Lock Operations
    // ===================================================================

    @Override
    public void lockInventory(
            LockInventoryRequest request, StreamObserver<LockInventoryResponse> responseObserver) {
        if (request.getItemsList().isEmpty()) {
            throw new InvalidArgumentException("Lock items list is empty");
        }
        if (request.getOrderId().isEmpty()) {
            throw InvalidArgumentException.required("order_id");
        }

        InventoryLock lock =
                inventoryService.lockInventory(
                        request.getItemsList(),
                        request.getOrderId(),
                        request.getLockTimeoutSeconds());

        responseObserver.onNext(LockInventoryResponse.newBuilder().setLock(lock).build());
        responseObserver.onCompleted();
    }

    @Override
    public void confirmLock(
            ConfirmLockRequest request, StreamObserver<ConfirmLockResponse> responseObserver) {
        if (request.getLockId().isEmpty()) {
            throw InvalidArgumentException.required("lock_id");
        }

        InventoryLock lock = inventoryService.confirmLock(request.getLockId());

        responseObserver.onNext(ConfirmLockResponse.newBuilder().setLock(lock).build());
        responseObserver.onCompleted();
    }

    @Override
    public void releaseLock(
            ReleaseLockRequest request, StreamObserver<ReleaseLockResponse> responseObserver) {
        if (request.getLockId().isEmpty()) {
            throw InvalidArgumentException.required("lock_id");
        }

        InventoryLock lock = inventoryService.releaseLock(request.getLockId(), request.getReason());

        responseObserver.onNext(ReleaseLockResponse.newBuilder().setLock(lock).build());
        responseObserver.onCompleted();
    }
}

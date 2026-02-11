package org.tripsphere.user.api.grpc.interceptor;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * gRPC interceptor for logging requests and responses. Logs method name, duration, and status for
 * each call.
 */
@Slf4j
@GrpcGlobalServerInterceptor
public class LoggingInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String methodName = call.getMethodDescriptor().getFullMethodName();
        long startTime = System.currentTimeMillis();

        log.debug("gRPC request: {}", methodName);

        // Wrap the ServerCall to intercept the response
        ServerCall<ReqT, RespT> wrappedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        long duration = System.currentTimeMillis() - startTime;

                        if (status.isOk()) {
                            log.info(
                                    "gRPC response: {} | status: OK | duration: {}ms",
                                    methodName,
                                    duration);
                        } else {
                            log.warn(
                                    "gRPC response: {} | status: {} | description: {} | duration:"
                                            + " {}ms",
                                    methodName,
                                    status.getCode(),
                                    status.getDescription(),
                                    duration);
                        }

                        super.close(status, trailers);
                    }
                };

        // Wrap the listener to log request messages
        ServerCall.Listener<ReqT> listener = next.startCall(wrappedCall, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onMessage(ReqT message) {
                if (log.isTraceEnabled()) {
                    log.trace("gRPC request message: {} | body: {}", methodName, message);
                }
                super.onMessage(message);
            }

            @Override
            public void onHalfClose() {
                super.onHalfClose();
            }

            @Override
            public void onCancel() {
                log.warn("gRPC call cancelled: {}", methodName);
                super.onCancel();
            }
        };
    }
}

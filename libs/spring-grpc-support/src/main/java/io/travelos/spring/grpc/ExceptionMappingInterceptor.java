package io.travelos.spring.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns exceptions thrown by service implementations into gRPC statuses. Without this, grpc-java
 * reports UNKNOWN with no message, which is useless to callers and hides bugs. Status exceptions
 * pass through; IllegalArgumentException becomes INVALID_ARGUMENT; IllegalStateException becomes
 * FAILED_PRECONDITION; everything else is INTERNAL and logged with its stack trace.
 */
public final class ExceptionMappingInterceptor implements ServerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(ExceptionMappingInterceptor.class);

  private final Function<Throwable, Status> mapper;

  public ExceptionMappingInterceptor() {
    this(ExceptionMappingInterceptor::defaultMapping);
  }

  public ExceptionMappingInterceptor(Function<Throwable, Status> mapper) {
    this.mapper = mapper;
  }

  public static Status defaultMapping(Throwable t) {
    return switch (t) {
      case StatusRuntimeException e -> e.getStatus();
      case StatusException e -> e.getStatus();
      case IllegalArgumentException e -> Status.INVALID_ARGUMENT.withDescription(e.getMessage());
      case IllegalStateException e -> Status.FAILED_PRECONDITION.withDescription(e.getMessage());
      default -> Status.INTERNAL.withDescription("internal error");
    };
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onHalfClose() {
        try {
          super.onHalfClose();
        } catch (RuntimeException e) {
          close(call, e);
        }
      }

      @Override
      public void onMessage(ReqT message) {
        try {
          super.onMessage(message);
        } catch (RuntimeException e) {
          close(call, e);
        }
      }
    };
  }

  private <ReqT, RespT> void close(ServerCall<ReqT, RespT> call, RuntimeException e) {
    Status status = mapper.apply(e);
    if (status.getCode() == Status.Code.INTERNAL) {
      log.error("unhandled exception in {}", call.getMethodDescriptor().getFullMethodName(), e);
    } else {
      log.debug("{} -> {}", call.getMethodDescriptor().getFullMethodName(), status);
    }
    call.close(status, new Metadata());
  }
}

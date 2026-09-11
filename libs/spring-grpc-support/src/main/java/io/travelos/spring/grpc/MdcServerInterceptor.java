package io.travelos.spring.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.MDC;

/**
 * Copies tracing headers into the logging MDC for the duration of each callback. Callers set {@code
 * tenant-id}, {@code correlation-id} and {@code principal} metadata; the message-level
 * RequestContext remains the authoritative copy and is validated by {@link RequestContexts}.
 */
public final class MdcServerInterceptor implements ServerInterceptor {

  public static final Metadata.Key<String> TENANT =
      Metadata.Key.of("tenant-id", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> CORRELATION =
      Metadata.Key.of("correlation-id", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> PRINCIPAL =
      Metadata.Key.of("principal", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String tenant = headers.get(TENANT);
    String correlation = headers.get(CORRELATION);
    String principal = headers.get(PRINCIPAL);
    String method = call.getMethodDescriptor().getFullMethodName();
    ServerCall.Listener<ReqT> delegate;
    try (MdcScope ignored = new MdcScope(tenant, correlation, principal, method)) {
      delegate = next.startCall(call, headers);
    }
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onMessage(ReqT message) {
        try (MdcScope ignored = new MdcScope(tenant, correlation, principal, method)) {
          super.onMessage(message);
        }
      }

      @Override
      public void onHalfClose() {
        try (MdcScope ignored = new MdcScope(tenant, correlation, principal, method)) {
          super.onHalfClose();
        }
      }

      @Override
      public void onCancel() {
        try (MdcScope ignored = new MdcScope(tenant, correlation, principal, method)) {
          super.onCancel();
        }
      }

      @Override
      public void onComplete() {
        try (MdcScope ignored = new MdcScope(tenant, correlation, principal, method)) {
          super.onComplete();
        }
      }
    };
  }

  private static final class MdcScope implements AutoCloseable {
    MdcScope(String tenant, String correlation, String principal, String method) {
      put("tenant_id", tenant);
      put("correlation_id", correlation);
      put("principal", principal);
      put("grpc_method", method);
    }

    private static void put(String key, String value) {
      if (value != null) {
        MDC.put(key, value);
      }
    }

    @Override
    public void close() {
      MDC.remove("tenant_id");
      MDC.remove("correlation_id");
      MDC.remove("principal");
      MDC.remove("grpc_method");
    }
  }
}

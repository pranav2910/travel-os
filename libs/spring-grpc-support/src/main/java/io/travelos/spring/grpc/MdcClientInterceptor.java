package io.travelos.spring.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;

/** Propagates the caller's MDC (tenant, correlation, principal) as call metadata. */
public final class MdcClientInterceptor implements ClientInterceptor {

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(
        next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        put(headers, MdcServerInterceptor.TENANT, MDC.get("tenant_id"));
        put(headers, MdcServerInterceptor.CORRELATION, MDC.get("correlation_id"));
        put(headers, MdcServerInterceptor.PRINCIPAL, MDC.get("principal"));
        super.start(responseListener, headers);
      }
    };
  }

  private static void put(Metadata headers, Metadata.Key<String> key, String value) {
    if (value != null && !value.isBlank()) {
      headers.put(key, value);
    }
  }
}

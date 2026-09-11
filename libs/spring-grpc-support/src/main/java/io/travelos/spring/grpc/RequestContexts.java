package io.travelos.spring.grpc;

import io.grpc.Status;
import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.common.v1.RequestContext;

/**
 * Validates the message-level {@link RequestContext} every internal call must carry. A call without
 * a tenant or a principal is rejected before any business code runs: there is no such thing as an
 * anonymous internal request.
 */
public final class RequestContexts {

  private RequestContexts() {}

  public record Validated(TenantId tenant, Principal principal, String correlationId) {}

  public static Validated require(RequestContext ctx) {
    if (ctx == null || ctx.getTenantId().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.tenant_id is required")
          .asRuntimeException();
    }
    TenantId tenant;
    try {
      tenant = TenantId.of(ctx.getTenantId());
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.tenant_id: " + e.getMessage())
          .asRuntimeException();
    }
    if (!ctx.hasPrincipal() || ctx.getPrincipal().getId().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.principal is required")
          .asRuntimeException();
    }
    Principal principal;
    try {
      principal = Principal.parse(ctx.getPrincipal().getId());
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.principal: " + e.getMessage())
          .asRuntimeException();
    }
    if (ctx.getCorrelationId().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.correlation_id is required")
          .asRuntimeException();
    }
    return new Validated(tenant, principal, ctx.getCorrelationId());
  }
}

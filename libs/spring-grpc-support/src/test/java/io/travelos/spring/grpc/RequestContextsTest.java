package io.travelos.spring.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.identity.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import org.junit.jupiter.api.Test;

class RequestContextsTest {

  @Test
  void acceptsACompleteContext() {
    RequestContexts.Validated validated =
        RequestContexts.require(
            RequestContext.newBuilder()
                .setTenantId("acme")
                .setCorrelationId("trip_1")
                .setPrincipal(
                    io.travelos.contracts.common.v1.Principal.newBuilder()
                        .setKind(io.travelos.contracts.common.v1.Principal.Kind.AGENT)
                        .setId("agent/trip-planner/v1"))
                .build());
    assertThat(validated.tenant().value()).isEqualTo("acme");
    assertThat(validated.principal()).isEqualTo(new Principal.Agent("trip-planner", "v1"));
    assertThat(validated.correlationId()).isEqualTo("trip_1");
  }

  @Test
  void rejectsMissingPieces() {
    assertInvalid(RequestContext.newBuilder().build(), "tenant_id");
    assertInvalid(RequestContext.newBuilder().setTenantId("Acme").build(), "tenant_id");
    assertInvalid(RequestContext.newBuilder().setTenantId("acme").build(), "principal");
    assertInvalid(
        RequestContext.newBuilder()
            .setTenantId("acme")
            .setPrincipal(io.travelos.contracts.common.v1.Principal.newBuilder().setId("root"))
            .build(),
        "principal");
    assertInvalid(
        RequestContext.newBuilder()
            .setTenantId("acme")
            .setPrincipal(
                io.travelos.contracts.common.v1.Principal.newBuilder().setId("human/alice"))
            .build(),
        "correlation_id");
  }

  private static void assertInvalid(RequestContext ctx, String field) {
    assertThatThrownBy(() -> RequestContexts.require(ctx))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
              assertThat(e.getStatus().getDescription()).contains(field);
            });
  }
}

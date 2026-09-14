package io.travelos.disruption.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.travelos.contracts.disruption.v1.AffectedSegment;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.disruption.v1.DisruptionSeverity;
import io.travelos.contracts.disruption.v1.DisruptionStatus;
import io.travelos.contracts.disruption.v1.DisruptionType;
import io.travelos.contracts.disruption.v1.RecoveryDecision;
import io.travelos.contracts.disruption.v1.RecoveryOutcome;
import io.travelos.contracts.disruption.v1.RecoveryState;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

final class ProtoMapping {

  private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

  private ProtoMapping() {}

  static Disruption toProto(
      io.travelos.disruption.model.Disruption d,
      Optional<String> decisionJson,
      Optional<String> outcomeJson) {
    Disruption.Builder b =
        Disruption.newBuilder()
            .setDisruptionId(d.disruptionId())
            .setTenantId(d.tenant().value())
            .setTripId(nullToEmpty(d.tripId()))
            .setOrderId(nullToEmpty(d.orderId()))
            .setSegmentId(nullToEmpty(d.segmentId()))
            .setType(
                enumOr(DisruptionType.class, d.type(), DisruptionType.DISRUPTION_TYPE_UNSPECIFIED))
            .setSupplier(d.supplier())
            .setSupplierEventId(d.supplierEventId())
            .setExternalOrderId(d.externalOrderId())
            .setTravelerId(nullToEmpty(d.travelerId()))
            .setDetectedAt(ts(d.detectedAt()))
            .setStatus(DisruptionStatus.valueOf(d.status().name()))
            .setSeverity(
                enumOr(
                    DisruptionSeverity.class,
                    d.severity(),
                    DisruptionSeverity.DISRUPTION_SEVERITY_UNSPECIFIED))
            .setRawReference(nullToEmpty(d.rawReference()))
            .setReason(nullToEmpty(d.reason()))
            .setAffected(merge(d.affectedJson(), AffectedSegment.newBuilder()).build())
            .setRecovery(recovery(d))
            .setVersion(d.version())
            .setCreatedAt(ts(d.createdAt()))
            .setUpdatedAt(ts(d.updatedAt()));
    decisionJson.ifPresent(j -> b.setDecision(merge(j, RecoveryDecision.newBuilder()).build()));
    outcomeJson.ifPresent(j -> b.setOutcome(merge(j, RecoveryOutcome.newBuilder()).build()));
    return b.build();
  }

  /** The stored state, plus the failure columns so a terminal disruption explains itself. */
  static RecoveryState recovery(io.travelos.disruption.model.Disruption d) {
    RecoveryState.Builder b = merge(d.recoveryJson(), RecoveryState.newBuilder());
    if (b.getFailureStage().isBlank() && d.failureStage() != null) {
      b.setFailureStage(d.failureStage());
    }
    if (b.getFailureCode().isBlank() && d.failureCode() != null) {
      b.setFailureCode(d.failureCode());
    }
    return b.build();
  }

  static <B extends com.google.protobuf.Message.Builder> B merge(String json, B builder) {
    try {
      PARSER.merge(json, builder);
    } catch (InvalidProtocolBufferException e) {
      // stored by us through JsonFormat; unreadable means a contract drift, surface the empty shape
    }
    return builder;
  }

  static <E extends Enum<E>> E enumOr(Class<E> type, @Nullable String name, E fallback) {
    if (name == null || name.isBlank()) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }

  static String nullToEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }
}

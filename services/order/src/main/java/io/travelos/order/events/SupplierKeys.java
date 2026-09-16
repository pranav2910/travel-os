package io.travelos.order.events;

import com.google.protobuf.util.JsonFormat;
import io.travelos.contracts.offer.v1.Offer;
import org.jspecify.annotations.Nullable;

/**
 * The supplier an outcome is attributed to (Slice 5): {@code air:<marketing carrier of the first
 * outbound segment>}, {@code hotel:<property id>}, {@code ground:<vendor id>}. The learning service
 * and the optimizer use the same vocabulary; a key never carries a person or free text.
 */
public final class SupplierKeys {
  private SupplierKeys() {}

  public static @Nullable String of(@Nullable String offerJson) {
    if (offerJson == null || offerJson.isBlank()) {
      return null;
    }
    Offer.Builder b = Offer.newBuilder();
    try {
      JsonFormat.parser().ignoringUnknownFields().merge(offerJson, b);
    } catch (Exception e) {
      return null;
    }
    return of(b.build());
  }

  public static @Nullable String of(Offer o) {
    if (o.hasAir() && o.getAir().getOutbound().getSegmentsCount() > 0) {
      String carrier = o.getAir().getOutbound().getSegments(0).getCarrier();
      return carrier.isBlank() ? null : "air:" + carrier;
    }
    if (o.hasHotel() && !o.getHotel().getPropertyId().isBlank()) {
      return "hotel:" + o.getHotel().getPropertyId();
    }
    if (o.hasGround() && !o.getGround().getVendorId().isBlank()) {
      return "ground:" + o.getGround().getVendorId();
    }
    return null;
  }
}

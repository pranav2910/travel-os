package io.travelos.supplier;

import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchGroundResponse;

/** A ground-transport adapter: scheduled transfers with a pickup inside a window (Slice 3). */
public interface GroundSupplier extends SupplierAdapter {
  SearchGroundResponse searchGround(SearchGroundRequest request);
}

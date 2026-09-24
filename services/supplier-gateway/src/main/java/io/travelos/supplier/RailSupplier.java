package io.travelos.supplier;

import io.travelos.contracts.supplier.v1.SearchRailRequest;
import io.travelos.contracts.supplier.v1.SearchRailResponse;

/**
 * Phase 4: a rail adapter (an operator or a rail aggregator), distinct from ground transfers. No
 * SIMULATED or LIVE adapter is registered yet; the gateway answers rail searches with NO_PROVIDER.
 */
public interface RailSupplier extends SupplierAdapter {
  SearchRailResponse searchRail(SearchRailRequest request);
}

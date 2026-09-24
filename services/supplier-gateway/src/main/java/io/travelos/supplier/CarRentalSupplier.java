package io.travelos.supplier;

import io.travelos.contracts.supplier.v1.SearchCarsRequest;
import io.travelos.contracts.supplier.v1.SearchCarsResponse;

/**
 * Phase 4: a car-rental adapter, distinct from chauffeured ground transfers. No SIMULATED or LIVE
 * adapter is registered yet; the gateway answers car searches with NO_PROVIDER.
 */
public interface CarRentalSupplier extends SupplierAdapter {
  SearchCarsResponse searchCars(SearchCarsRequest request);
}

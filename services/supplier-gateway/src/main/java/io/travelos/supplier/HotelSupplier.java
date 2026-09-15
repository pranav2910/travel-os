package io.travelos.supplier;

import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;

/** A hotel adapter: properties in a city for local check-in/check-out dates (Slice 3). */
public interface HotelSupplier extends SupplierAdapter {
  SearchHotelsResponse searchHotels(SearchHotelsRequest request);
}

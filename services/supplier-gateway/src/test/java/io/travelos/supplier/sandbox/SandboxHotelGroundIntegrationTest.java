package io.travelos.supplier.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.GetCapabilitiesRequest;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchGroundResponse;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The SIMULATED hotel and ground suppliers over real gRPC and a real ledger: deterministic
 * catalogs, local-calendar nights, cancellation terms, idempotent bookings, status lookup after a
 * lost answer, re-timing/re-dating on the same vendor, and the documented fault fixtures.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SandboxHotelGroundIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Autowired GrpcServerLifecycle grpc;
  @Autowired SandboxBookingRepository bookings;

  private ManagedChannel channel;
  private SupplierGatewayGrpc.SupplierGatewayBlockingStub gateway;

  @BeforeAll
  void setUp() {
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    gateway = SupplierGatewayGrpc.newBlockingStub(channel);
  }

  @AfterAll
  void tearDown() {
    channel.shutdownNow();
  }

  @Test
  void hotelsAreListedOnThePropertysCalendarWithTheirTerms() {
    SearchHotelsResponse seattle = gateway.searchHotels(hotels("SEA", "2026-10-06", "2026-10-08"));
    assertThat(seattle.getOffersCount()).isEqualTo(5);
    Offer cheapest = cheapest(seattle.getOffersList());
    assertThat(cheapest.getHotel().getName()).isEqualTo("Budget Inn");
    assertThat(cheapest.getType()).isEqualTo(OfferType.HOTEL);
    assertThat(cheapest.getComponentId()).isEmpty();
    assertThat(cheapest.getTotal().getAmountMinor()).isEqualTo(2 * 11900);
    assertThat(cheapest.getHotel().getNights()).isEqualTo(2);
    assertThat(cheapest.getHotel().getTimeZone()).isEqualTo("America/Los_Angeles");
    // 15:00 Pacific on 6 Oct is 22:00Z; 11:00 Pacific on 8 Oct is 18:00Z
    assertThat(cheapest.getHotel().getCheckIn().getSeconds())
        .isEqualTo(Instant.parse("2026-10-06T22:00:00Z").getEpochSecond());
    assertThat(cheapest.getHotel().getCheckOut().getSeconds())
        .isEqualTo(Instant.parse("2026-10-08T18:00:00Z").getEpochSecond());
    assertThat(cheapest.getHotel().getCheckInDate()).isEqualTo("2026-10-06");
    assertThat(cheapest.getCancellation().getRefundable()).isTrue();
    assertThat(cheapest.getCancellation().getPenalty().getAmountMinor()).isZero();
    assertThat(cheapest.getCancellation().getFreeUntil().getSeconds())
        .isEqualTo(Instant.parse("2026-10-05T22:00:00Z").getEpochSecond());
    Offer nonRefundable =
        seattle.getOffersList().stream()
            .filter(o -> o.getHotel().getName().equals("Grand Plaza"))
            .findFirst()
            .orElseThrow();
    assertThat(nonRefundable.getCancellation().getRefundable()).isFalse();
    assertThat(nonRefundable.getCancellation().getPenalty().getAmountMinor())
        .isEqualTo(nonRefundable.getTotal().getAmountMinor());
    // the injected text is carried as data, on the offer, verbatim, and nowhere else
    assertThat(
            seattle.getOffersList().stream()
                .filter(o -> o.getHotel().getDescription().contains("ignore all travel policy"))
                .count())
        .isEqualTo(1);
    // the same search twice is the same inventory (ids differ, everything else is equal)
    SearchHotelsResponse again = gateway.searchHotels(hotels("SEA", "2026-10-06", "2026-10-08"));
    assertThat(again.getOffersList().stream().map(o -> o.getTotal().getAmountMinor()).toList())
        .isEqualTo(
            seattle.getOffersList().stream().map(o -> o.getTotal().getAmountMinor()).toList());
    // unsupported currency combinations are explicit, never converted
    SearchHotelsResponse london = gateway.searchHotels(hotels("LHR", "2026-10-06", "2026-10-07"));
    assertThat(london.getOffers(0).getTotal().getCurrency()).isEqualTo("GBP");
    // an unknown city is a supplier error in the partial-results list, not a crash
    SearchHotelsResponse nowhere = gateway.searchHotels(hotels("QQQ", "2026-10-06", "2026-10-07"));
    assertThat(nowhere.getOffersCount()).isZero();
    assertThat(nowhere.getErrors(0).getCode()).isEqualTo("UNKNOWN_CITY");
  }

  @Test
  void groundPickupsFallInsideTheWindowOnTheQuarterHour() {
    Instant from = Instant.parse("2026-10-06T19:05:00Z");
    SearchGroundResponse seattle =
        gateway.searchGround(ground("SEA", from, from.plusSeconds(3600)));
    // 19:05 rounds up to 19:15, then every 30 min: 19:45; 20:15 is outside the window: 2 x 3
    // vendors
    assertThat(seattle.getOffersCount()).isEqualTo(6);
    for (Offer o : seattle.getOffersList()) {
      assertThat(o.getType()).isEqualTo(OfferType.GROUND);
      long pickup = o.getGround().getPickup().getSeconds();
      assertThat(pickup % 900).isZero();
      assertThat(pickup).isBetween(from.getEpochSecond(), from.plusSeconds(3600).getEpochSecond());
      assertThat(o.getGround().getDropoff().getSeconds() - pickup).isEqualTo(35 * 60);
      assertThat(o.getGround().getTimeZone()).isEqualTo("America/Los_Angeles");
      assertThat(o.getTotal().getCurrency()).isEqualTo("USD");
    }
    assertThat(cheapest(seattle.getOffersList()).getGround().getVendorName())
        .isEqualTo("CityShuttle");
  }

  @Test
  void quotesRevalidatePriceAndExpiry() {
    // SFO's cheapest property re-prices on revalidation: the approved plan is stale
    Offer saver =
        cheapest(gateway.searchHotels(hotels("SFO", "2026-10-06", "2026-10-08")).getOffersList());
    assertThat(saver.getHotel().getNightlyRate().getAmountMinor()).isEqualTo(14900);
    QuoteOfferResponse quoted = gateway.quoteOffer(quote(saver));
    assertThat(quoted.getPriceChanged()).isTrue();
    assertThat(quoted.getRequoted()).isFalse();
    assertThat(quoted.getOffer().getHotel().getNightlyRate().getAmountMinor()).isEqualTo(18900);
    assertThat(quoted.getOffer().getTotal().getAmountMinor()).isEqualTo(2 * 18900);
    // ORD's flash rate is quoted for ten seconds; afterwards the supplier re-quotes it
    SandboxStayId stale =
        new SandboxStayId(
            "ORD",
            LocalDate.of(2026, 10, 6),
            LocalDate.of(2026, 10, 7),
            "SHORTQUOTE",
            Instant.now().minusSeconds(30).getEpochSecond());
    QuoteOfferResponse requoted =
        gateway.quoteOffer(
            QuoteOfferRequest.newBuilder()
                .setCtx(ctx(""))
                .setProvider("sandbox-hotel")
                .setProviderOfferId(stale.encode())
                .build());
    assertThat(requoted.getRequoted()).isTrue();
    assertThat(requoted.getPriceChanged()).isFalse();
    assertThat(requoted.getOffer().getExpiresAt().getSeconds())
        .isGreaterThan(Instant.now().getEpochSecond());
    // and a stale quote cannot be booked as it stands
    assertThatThrownBy(() -> gateway.createOrder(book(stale.encode(), "sandbox-hotel", "k-stale")))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("OFFER_EXPIRED");
            });
    // a ground quote is the same contract
    Offer shuttle =
        cheapest(
            gateway
                .searchGround(
                    ground(
                        "SEA",
                        Instant.parse("2026-10-06T19:00:00Z"),
                        Instant.parse("2026-10-06T20:00:00Z")))
                .getOffersList());
    QuoteOfferResponse groundQuote = gateway.quoteOffer(quote(shuttle));
    assertThat(groundQuote.getPriceChanged()).isFalse();
    assertThat(groundQuote.getOffer().getGround().getPickup())
        .isEqualTo(shuttle.getGround().getPickup());
    // PriceOffer is the air-only form; asking it about a hotel is a caller bug, not a fault
    assertThatThrownBy(
            () ->
                gateway.priceOffer(
                    PriceOfferRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("sandbox-hotel")
                        .setProviderOfferId(saver.getProviderOfferId())
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  void aLostAnswerIsReconciledByStatusLookupAndNeverBookedTwice() {
    Offer inn =
        cheapest(gateway.searchHotels(hotels("DEN", "2026-10-06", "2026-10-07")).getOffersList());
    assertThat(inn.getHotel().getName()).isEqualTo("Front Range Inn");
    String key = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV:itm-den";
    long before = bookings.count("HOTEL", "acme");
    assertThatThrownBy(
            () -> gateway.createOrder(book(inn.getProviderOfferId(), "sandbox-hotel", key)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
              assertThat(e.getStatus().getDescription()).startsWith("TIMEOUT");
            });
    // the supplier DID book it: the answer was lost, not the room
    assertThat(bookings.count("HOTEL", "acme")).isEqualTo(before + 1);
    BookingStatus status =
        gateway.getBookingStatus(
            GetBookingStatusRequest.newBuilder()
                .setCtx(ctx(""))
                .setProvider("sandbox-hotel")
                .setIdempotencyKey(key)
                .build());
    assertThat(status.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(status.getExternalOrderId()).startsWith("SBH-");
    assertThat(status.getCharged().getAmountMinor()).isEqualTo(12900);
    // a blind retry with the same key is the same booking, too
    CreateOrderResponse retried =
        gateway.createOrder(book(inn.getProviderOfferId(), "sandbox-hotel", key));
    assertThat(retried.getExternalOrderId()).isEqualTo(status.getExternalOrderId());
    assertThat(bookings.count("HOTEL", "acme")).isEqualTo(before + 1);
    // a key the supplier never saw is NOT_FOUND: safe to send the command
    assertThat(
            gateway
                .getBookingStatus(
                    GetBookingStatusRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("sandbox-hotel")
                        .setIdempotencyKey("never-sent")
                        .build())
                .getStatus())
        .isEqualTo(SupplierOrderStatus.NOT_FOUND);
    // another tenant cannot see it by id
    assertThat(
            gateway
                .getBookingStatus(
                    GetBookingStatusRequest.newBuilder()
                        .setCtx(ctx("").toBuilder().setTenantId("globex"))
                        .setProvider("sandbox-hotel")
                        .setExternalOrderId(status.getExternalOrderId())
                        .build())
                .getStatus())
        .isEqualTo(SupplierOrderStatus.NOT_FOUND);
  }

  @Test
  void refusalsAreFinalAndNamed() {
    Offer motel =
        cheapest(gateway.searchHotels(hotels("AUS", "2026-10-06", "2026-10-07")).getOffersList());
    assertThatThrownBy(
            () -> gateway.createOrder(book(motel.getProviderOfferId(), "sandbox-hotel", "k-aus")))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("ROOM_NO_LONGER_AVAILABLE");
            });
    Offer rides =
        cheapest(
            gateway
                .searchGround(
                    ground(
                        "LAX",
                        Instant.parse("2026-10-06T19:00:00Z"),
                        Instant.parse("2026-10-06T20:00:00Z")))
                .getOffersList());
    assertThatThrownBy(
            () ->
                gateway.createOrder(book(rides.getProviderOfferId(), "sandbox-ground", "k-lax-g")))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e ->
                assertThat(e.getStatus().getDescription())
                    .startsWith("VEHICLE_NO_LONGER_AVAILABLE"));
    // LAX's cheapest hotel books fine and then refuses to be cancelled: the exposure case
    Offer wilshire =
        cheapest(gateway.searchHotels(hotels("LAX", "2026-10-06", "2026-10-07")).getOffersList());
    CreateOrderResponse booked =
        gateway.createOrder(book(wilshire.getProviderOfferId(), "sandbox-hotel", "k-lax-h"));
    assertThat(booked.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThatThrownBy(
            () -> gateway.cancelOrder(cancel(booked.getExternalOrderId(), "sandbox-hotel")))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("CANCELLATION_REFUSED");
            });
    assertThat(bookings.byId("acme", booked.getExternalOrderId()).orElseThrow().status())
        .isEqualTo("CONFIRMED");
  }

  @Test
  void cancellationFollowsTheTermsAndIsIdempotent() {
    List<Offer> seattle =
        gateway.searchHotels(hotels("SEA", "2026-10-06", "2026-10-08")).getOffersList();
    Offer refundable = cheapest(seattle);
    Offer nonRefundable =
        seattle.stream().filter(o -> !o.getRefundable()).findFirst().orElseThrow();
    CreateOrderResponse a =
        gateway.createOrder(book(refundable.getProviderOfferId(), "sandbox-hotel", "k-c1"));
    CreateOrderResponse b =
        gateway.createOrder(book(nonRefundable.getProviderOfferId(), "sandbox-hotel", "k-c2"));
    CancelOrderResponse ra = gateway.cancelOrder(cancel(a.getExternalOrderId(), "sandbox-hotel"));
    assertThat(ra.getRefund().getAmountMinor()).isEqualTo(a.getCharged().getAmountMinor());
    CancelOrderResponse rb = gateway.cancelOrder(cancel(b.getExternalOrderId(), "sandbox-hotel"));
    assertThat(rb.getRefund().getAmountMinor()).isZero();
    CancelOrderResponse twice =
        gateway.cancelOrder(cancel(a.getExternalOrderId(), "sandbox-hotel"));
    assertThat(twice.getStatus()).isEqualTo(SupplierOrderStatus.CANCELLED);
    assertThat(twice.getRefund().getAmountMinor()).isZero();
    assertThat(
            gateway
                .getBookingStatus(
                    GetBookingStatusRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("sandbox-hotel")
                        .setExternalOrderId(a.getExternalOrderId())
                        .build())
                .getStatus())
        .isEqualTo(SupplierOrderStatus.CANCELLED);
  }

  @Test
  void datesAndPickupsChangeOnTheSameVendorOnlyOncePerKey() {
    Offer twoNights =
        cheapest(gateway.searchHotels(hotels("SEA", "2026-10-06", "2026-10-08")).getOffersList());
    CreateOrderResponse booked =
        gateway.createOrder(book(twoNights.getProviderOfferId(), "sandbox-hotel", "k-chg"));
    Offer threeNights =
        gateway.searchHotels(hotels("SEA", "2026-10-06", "2026-10-09")).getOffersList().stream()
            .filter(o -> o.getHotel().getPropertyId().equals(twoNights.getHotel().getPropertyId()))
            .findFirst()
            .orElseThrow();
    ChangeOrderRequest change =
        ChangeOrderRequest.newBuilder()
            .setCtx(ctx("k-chg:CHANGE:1"))
            .setProvider("sandbox-hotel")
            .setExternalOrderId(booked.getExternalOrderId())
            .setNewProviderOfferId(threeNights.getProviderOfferId())
            .setPaymentToken("tok")
            .build();
    ChangeOrderResponse first = gateway.changeOrder(change);
    ChangeOrderResponse second = gateway.changeOrder(change);
    assertThat(first.getStatus()).isEqualTo(SupplierOrderStatus.CHANGED);
    assertThat(first.getIncrementalCost().getAmountMinor()).isEqualTo(11900);
    assertThat(first.getChargedTotal().getAmountMinor()).isEqualTo(3 * 11900);
    assertThat(second).isEqualTo(first);
    Offer elsewhere =
        gateway.searchHotels(hotels("SEA", "2026-10-06", "2026-10-09")).getOffersList().stream()
            .filter(o -> !o.getHotel().getPropertyId().equals(twoNights.getHotel().getPropertyId()))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                gateway.changeOrder(
                    change.toBuilder()
                        .setCtx(ctx("k-chg:CHANGE:2"))
                        .setNewProviderOfferId(elsewhere.getProviderOfferId())
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).startsWith("CHANGE_NOT_SUPPORTED"));
    // ground: same vendor, later pickup, no extra charge
    Instant from = Instant.parse("2026-10-06T19:00:00Z");
    List<Offer> shuttles =
        gateway.searchGround(ground("SEA", from, from.plusSeconds(3600))).getOffersList().stream()
            .filter(o -> o.getGround().getVendorName().equals("CityShuttle"))
            .sorted(Comparator.comparingLong(o -> o.getGround().getPickup().getSeconds()))
            .toList();
    CreateOrderResponse ride =
        gateway.createOrder(book(shuttles.get(0).getProviderOfferId(), "sandbox-ground", "k-ride"));
    ChangeOrderResponse retimed =
        gateway.changeOrder(
            ChangeOrderRequest.newBuilder()
                .setCtx(ctx("k-ride:CHANGE:1"))
                .setProvider("sandbox-ground")
                .setExternalOrderId(ride.getExternalOrderId())
                .setNewProviderOfferId(shuttles.get(2).getProviderOfferId())
                .build());
    assertThat(retimed.getIncrementalCost().getAmountMinor()).isZero();
    assertThat(bookings.byId("acme", ride.getExternalOrderId()).orElseThrow().providerOfferId())
        .isEqualTo(shuttles.get(2).getProviderOfferId());
  }

  @Test
  void adaptersDeclareWhatTheyAre() {
    SupplierCapabilities hotel = gateway.getCapabilities(capabilities("sandbox-hotel"));
    assertThat(hotel.getTypesList()).containsExactly(OfferType.HOTEL);
    assertThat(hotel.getIntegration()).isEqualTo("SIMULATED");
    assertThat(hotel.getChangeSupported()).isTrue();
    assertThat(hotel.getStatusLookupSupported()).isTrue();
    assertThat(hotel.getNotificationsSupported()).isFalse();
    SupplierCapabilities ground = gateway.getCapabilities(capabilities("sandbox-ground"));
    assertThat(ground.getTypesList()).containsExactly(OfferType.GROUND);
    SupplierCapabilities air = gateway.getCapabilities(capabilities("sandbox-air"));
    assertThat(air.getTypesList()).containsExactly(OfferType.AIR);
    assertThat(air.getIntegration()).isEqualTo("SIMULATED");
    assertThat(air.getNotificationsSupported()).isTrue();
    assertThatThrownBy(() -> gateway.getCapabilities(capabilities("ndc-nowhere")))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
  }

  // ------------------------------------------------------------------ helpers

  private static Offer cheapest(List<Offer> offers) {
    return offers.stream()
        .min(Comparator.comparingLong((Offer o) -> o.getTotal().getAmountMinor()))
        .orElseThrow();
  }

  private static SearchHotelsRequest hotels(String city, String in, String out) {
    return SearchHotelsRequest.newBuilder()
        .setCtx(ctx(""))
        .setCity(city)
        .setCheckInDate(in)
        .setCheckOutDate(out)
        .setGuests(1)
        .build();
  }

  private static SearchGroundRequest ground(String city, Instant from, Instant to) {
    return SearchGroundRequest.newBuilder()
        .setCtx(ctx(""))
        .setCity(city)
        .setKind("AIRPORT_TO_HOTEL")
        .setPickup(TimeWindow.newBuilder().setNotBefore(ts(from)).setNotAfter(ts(to)))
        .setPassengers(1)
        .build();
  }

  private static QuoteOfferRequest quote(Offer o) {
    return QuoteOfferRequest.newBuilder()
        .setCtx(ctx(""))
        .setProvider(o.getProvider())
        .setProviderOfferId(o.getProviderOfferId())
        .build();
  }

  private static CreateOrderRequest book(String providerOfferId, String provider, String key) {
    return CreateOrderRequest.newBuilder()
        .setCtx(ctx(key))
        .setProvider(provider)
        .setProviderOfferId(providerOfferId)
        .addPassengers(
            Passenger.newBuilder().setGivenName("Alice").setFamilyName("Nguyen").setEmail("a@x"))
        .setPaymentToken("tok_visa_4242")
        .build();
  }

  private static CancelOrderRequest cancel(String externalOrderId, String provider) {
    return CancelOrderRequest.newBuilder()
        .setCtx(ctx(""))
        .setProvider(provider)
        .setExternalOrderId(externalOrderId)
        .build();
  }

  private static GetCapabilitiesRequest capabilities(String provider) {
    return GetCapabilitiesRequest.newBuilder().setCtx(ctx("")).setProvider(provider).build();
  }

  private static RequestContext ctx(String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.SERVICE).setId("service/order"))
        .build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).build();
  }
}

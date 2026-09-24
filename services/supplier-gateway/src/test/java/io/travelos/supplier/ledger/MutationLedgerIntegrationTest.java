package io.travelos.supplier.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Cabin;
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
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchCarsRequest;
import io.travelos.contracts.supplier.v1.SearchRailRequest;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.SupplierAdapter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 4 (ADR-0016): every mutation is in the ledger before the supplier is called; a retry with
 * the same key is answered from the ledger; a lost answer is reconciled by lookup when the adapter
 * can, retried when the supplier honours the key, and otherwise a known unknown that nobody
 * retries.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(MutationLedgerIntegrationTest.Adapters.class)
class MutationLedgerIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  /**
   * Two scripted adapters beside the sandbox: one that loses answers and cannot be asked, one that
   * can.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class Adapters {
    @Bean
    LossyAdapter lossyAdapter() {
      return new LossyAdapter("lossy-hotel", false);
    }

    @Bean
    LossyAdapter reconcilingAdapter() {
      return new LossyAdapter("reconciling-hotel", true);
    }
  }

  /** Loses the answer to its first CreateOrder (the booking happens), answers after that. */
  static final class LossyAdapter implements SupplierAdapter {
    final String provider;
    final boolean canLookUp;
    final AtomicInteger bookings = new AtomicInteger();
    volatile boolean loseNext = true;

    LossyAdapter(String provider, boolean canLookUp) {
      this.provider = provider;
      this.canLookUp = canLookUp;
    }

    @Override
    public String provider() {
      return provider;
    }

    @Override
    public SupplierCapabilities capabilities() {
      return SupplierCapabilities.newBuilder()
          .setProvider(provider)
          .addTypes(OfferType.HOTEL)
          .setCancelSupported(true)
          .setIntegration("SIMULATED")
          .setMutationsIdempotent(false)
          .setStatusLookupSupported(canLookUp)
          .setReconciliationByKeySupported(canLookUp)
          .build();
    }

    @Override
    public QuoteOfferResponse quote(QuoteOfferRequest request) {
      return QuoteOfferResponse.getDefaultInstance();
    }

    @Override
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
      bookings.incrementAndGet();
      if (loseNext) {
        loseNext = false;
        throw new SupplierException(
            "TRANSPORT", "the answer was lost after the booking was made", true);
      }
      return CreateOrderResponse.newBuilder()
          .setExternalOrderId(provider + "-" + bookings.get())
          .setStatus(SupplierOrderStatus.CONFIRMED)
          .build();
    }

    @Override
    public ChangeOrderResponse changeOrder(ChangeOrderRequest request) {
      throw new SupplierException("CHANGE_NOT_SUPPORTED", "no", false);
    }

    @Override
    public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
      return CancelOrderResponse.newBuilder()
          .setExternalOrderId(request.getExternalOrderId())
          .setStatus(SupplierOrderStatus.CANCELLED)
          .build();
    }

    @Override
    public BookingStatus bookingStatus(GetBookingStatusRequest request) {
      if (!canLookUp || bookings.get() == 0) {
        return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
      }
      return BookingStatus.newBuilder()
          .setStatus(SupplierOrderStatus.CONFIRMED)
          .setExternalOrderId(provider + "-" + bookings.get())
          .setRecordLocator("LOOKED-UP")
          .build();
    }
  }

  @Autowired GrpcServerLifecycle grpc;
  @Autowired JdbcClient jdbc;
  @Autowired LossyAdapter lossyAdapter;
  @Autowired LossyAdapter reconcilingAdapter;
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
  void aRetryWithTheSameKeyIsAnsweredFromTheLedgerWithoutASecondSupplierCall() {
    Offer offer = gateway.searchAir(search()).getOffers(0);
    String key = "trip_x:CREATE-ORDER:" + UUID.randomUUID();
    CreateOrderRequest create = createRequest("sandbox-air", offer.getProviderOfferId(), key);
    CreateOrderResponse first = gateway.createOrder(create);
    CreateOrderResponse second = gateway.createOrder(create);
    assertThat(second).isEqualTo(first);
    assertThat(ledger("sandbox-air", key))
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("calls", 1);
    assertThat(ledger("sandbox-air", key).get("external_ref"))
        .isEqualTo(first.getExternalOrderId());
    // the same key for a different request is a caller bug, not a second booking
    CreateOrderRequest different =
        create.toBuilder()
            .addPassengers(
                Passenger.newBuilder().setGivenName("Bob").setFamilyName("Chen").setEmail("b@c.d"))
            .build();
    assertThatThrownBy(() -> gateway.createOrder(different))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e -> {
              assertThat(((StatusRuntimeException) e).getStatus().getCode())
                  .isEqualTo(Status.Code.INVALID_ARGUMENT);
              assertThat(((StatusRuntimeException) e).getStatus().getDescription())
                  .contains("IDEMPOTENCY_KEY_REUSED");
            });
    // cancelling twice is one supplier call and one answer too
    CancelOrderRequest cancel =
        CancelOrderRequest.newBuilder()
            .setCtx(ctx(""))
            .setProvider("sandbox-air")
            .setExternalOrderId(first.getExternalOrderId())
            .build();
    CancelOrderResponse c1 = gateway.cancelOrder(cancel);
    CancelOrderResponse c2 = gateway.cancelOrder(cancel);
    assertThat(c2).isEqualTo(c1);
    assertThat(ledger("sandbox-air", "cancel:" + first.getExternalOrderId()))
        .containsEntry("status", "SUCCEEDED");
  }

  @Test
  void aLostAnswerFromASupplierThatCannotBeAskedIsAKnownUnknownNobodyRetries() {
    String key = "trip_y:CREATE-ORDER:" + UUID.randomUUID();
    CreateOrderRequest create = createRequest("lossy-hotel", "RATE-1", key);
    assertThatThrownBy(() -> gateway.createOrder(create))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .as("the first loss is reported as retryable")
                    .isEqualTo(Status.Code.UNAVAILABLE));
    assertThat(ledger("lossy-hotel", key)).containsEntry("status", "UNKNOWN");
    // the retry never reaches the supplier: it may already have booked
    assertThatThrownBy(() -> gateway.createOrder(create))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e -> {
              assertThat(((StatusRuntimeException) e).getStatus().getCode())
                  .isEqualTo(Status.Code.ABORTED);
              assertThat(((StatusRuntimeException) e).getStatus().getDescription())
                  .contains("OUTCOME_UNKNOWN");
            });
    assertThat(lossyAdapter.bookings.get())
        .as("exactly one booking was ever attempted")
        .isEqualTo(1);
    assertThat(ledger("lossy-hotel", key)).containsEntry("status", "UNKNOWN");
  }

  @Test
  void aLostAnswerFromASupplierThatCanBeAskedIsReconciledByLookupNotBookedTwice() {
    String key = "trip_z:CREATE-ORDER:" + UUID.randomUUID();
    CreateOrderRequest create = createRequest("reconciling-hotel", "RATE-2", key);
    assertThatThrownBy(() -> gateway.createOrder(create))
        .isInstanceOf(StatusRuntimeException.class);
    CreateOrderResponse adopted = gateway.createOrder(create);
    assertThat(adopted.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(adopted.getRecordLocator()).isEqualTo("LOOKED-UP");
    assertThat(reconcilingAdapter.bookings.get()).isEqualTo(1);
    assertThat(ledger("reconciling-hotel", key)).containsEntry("status", "SUCCEEDED");
    assertThat(gateway.createOrder(create)).as("and again from the ledger").isEqualTo(adopted);
  }

  @Test
  void railAndCarAreDistinctKindsWithNoProviderYet() {
    var rail =
        gateway.searchRail(
            SearchRailRequest.newBuilder()
                .setCtx(ctx(""))
                .setOrigin("BOS")
                .setDestination("NYP")
                .build());
    assertThat(rail.getOffersList()).isEmpty();
    assertThat(rail.getErrors(0).getCode()).isEqualTo("NO_PROVIDER");
    var cars =
        gateway.searchCars(
            SearchCarsRequest.newBuilder().setCtx(ctx("")).setPickupLocation("SEA").build());
    assertThat(cars.getErrors(0).getCode()).isEqualTo("NO_PROVIDER");
    SupplierCapabilities sandbox =
        gateway.getCapabilities(
            io.travelos.contracts.supplier.v1.GetCapabilitiesRequest.newBuilder()
                .setCtx(ctx(""))
                .setProvider("sandbox-air")
                .build());
    assertThat(sandbox.getIntegration()).isEqualTo("SIMULATED");
    assertThat(sandbox.getMutationsIdempotent()).isTrue();
    assertThat(sandbox.getReconciliationByKeySupported()).isTrue();
  }

  // ------------------------------------------------------------------ helpers

  private java.util.Map<String, Object> ledger(String provider, String key) {
    return jdbc.sql(
            "SELECT status, calls, external_ref FROM supplier_mutation_attempt WHERE provider = :p AND idempotency_key = :k")
        .param("p", provider)
        .param("k", key)
        .query(
            (rs, i) -> {
              java.util.Map<String, Object> m = new java.util.HashMap<>();
              m.put("status", rs.getString("status"));
              m.put("calls", rs.getInt("calls"));
              m.put("external_ref", rs.getString("external_ref"));
              return m;
            })
        .single();
  }

  private static CreateOrderRequest createRequest(
      String provider, String providerOfferId, String key) {
    return CreateOrderRequest.newBuilder()
        .setCtx(ctx(key))
        .setProvider(provider)
        .setProviderOfferId(providerOfferId)
        .addPassengers(
            Passenger.newBuilder()
                .setGivenName("Alice")
                .setFamilyName("Nguyen")
                .setEmail("alice@acme.example"))
        .setPaymentToken("tok_corp_visa_sandbox")
        .build();
  }

  private static SearchAirRequest search() {
    Instant out = Instant.parse("2026-10-06T00:00:00Z");
    return SearchAirRequest.newBuilder()
        .setCtx(ctx(""))
        .setOrigin("BOS")
        .setDestination("SEA")
        .setPassengers(1)
        .addCabins(Cabin.ECONOMY)
        .setOutboundDeparture(
            TimeWindow.newBuilder().setNotBefore(ts(out)).setNotAfter(ts(out.plusSeconds(86_000))))
        .build();
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

  static List<String> none() {
    return List.of();
  }
}

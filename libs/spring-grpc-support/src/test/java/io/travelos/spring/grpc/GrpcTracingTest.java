package io.travelos.spring.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two interceptors this library installs, against a real OpenTelemetry SDK: a client call made
 * inside a span reaches the server as a child of that span, and RequestContexts tags the server
 * span with tenant and trip so traces can be found by trip id.
 */
class GrpcTracingTest {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private OpenTelemetrySdk otel;
  private Server server;
  private ManagedChannel channel;

  @BeforeEach
  void setUp() throws Exception {
    otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    io.opentelemetry.api.trace.Tracer otelTracer = otel.getTracer("test");
    OtelTracer tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {});
    OtelPropagator propagator = new OtelPropagator(otel.getPropagators(), otelTracer);
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));

    String name = "tracing-" + System.nanoTime();
    server =
        InProcessServerBuilder.forName(name)
            .intercept(new ObservationGrpcServerInterceptor(registry))
            .addService(
                new TravelCoreServiceGrpc.TravelCoreServiceImplBase() {
                  @Override
                  public void getTrip(GetTripRequest request, StreamObserver<Trip> observer) {
                    RequestContexts.require(request.getCtx());
                    observer.onNext(Trip.newBuilder().setTripId(request.getTripId()).build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(name)
            .intercept(new ObservationGrpcClientInterceptor(registry))
            .build();
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
    otel.close();
  }

  @Test
  void aCallInsideASpanArrivesAsItsChildAndIsTaggedWithTheTrip() {
    Span root = otel.getTracer("test").spanBuilder("POST /api/v1/trips").startSpan();
    try (Scope ignored = root.makeCurrent()) {
      TravelCoreServiceGrpc.newBlockingStub(channel)
          .getTrip(
              GetTripRequest.newBuilder()
                  .setCtx(
                      RequestContext.newBuilder()
                          .setTenantId("acme")
                          .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                          .setPrincipal(
                              Principal.newBuilder()
                                  .setKind(Principal.Kind.AGENT)
                                  .setId("agent/trip-planner/v1")))
                  .setTripId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                  .build());
    } finally {
      root.end();
    }

    // The client span ends on the transport thread after the blocking call returns: wait for it.
    await().atMost(Duration.ofSeconds(10)).until(() -> exporter.getFinishedSpanItems().size() >= 3);
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertThat(spans).hasSize(3);
    SpanData client =
        spans.stream().filter(s -> s.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
    SpanData serverSpan =
        spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
    assertThat(client.getTraceId()).isEqualTo(root.getSpanContext().getTraceId());
    assertThat(serverSpan.getTraceId())
        .as("one trace across the hop")
        .isEqualTo(client.getTraceId());
    assertThat(serverSpan.getParentSpanId()).isEqualTo(client.getSpanId());
    assertThat(serverSpan.getName()).contains("TravelCoreService");
    assertThat(serverSpan.getAttributes().asMap().toString())
        .contains("trip.id")
        .contains("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .contains("tenant.id")
        .contains("agent/trip-planner/v1");
  }
}

"""Entry point: serve LlmGateway with health + reflection on GRPC_PORT (default 9087).

Environment:
  LLM_PROVIDER                  anthropic | fake (default: anthropic if ANTHROPIC_API_KEY is set)
  LLM_MODEL                     model id for the anthropic provider (default claude-opus-5)
  LLM_TENANT_DAILY_BUDGET_USD   per-tenant daily model spend ceiling (default 5)
"""

from __future__ import annotations

import logging
import os
import signal
import threading
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection

from travelos.llm.v1 import llm_pb2, llm_pb2_grpc
from travelos_llm_gateway.budget import TenantBudget
from travelos_llm_gateway.providers import Provider, provider_from_env
from travelos_llm_gateway.service import LlmGatewayService

log = logging.getLogger("travelos.llm-gateway")

SERVICE_NAME = llm_pb2.DESCRIPTOR.services_by_name["LlmGateway"].full_name


def build_server(
    port: int,
    provider: Provider | None = None,
    budget: TenantBudget | None = None,
    max_workers: int = 8,
) -> tuple[grpc.Server, int]:
    provider = provider or provider_from_env(dict(os.environ))
    if budget is None:
        usd = float(os.environ.get("LLM_TENANT_DAILY_BUDGET_USD", "5"))
        budget = TenantBudget(int(usd * 1_000_000))
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=max_workers))
    llm_pb2_grpc.add_LlmGatewayServicer_to_server(LlmGatewayService(provider, budget), server)
    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    reflection.enable_server_reflection(
        (SERVICE_NAME, health.SERVICE_NAME, reflection.SERVICE_NAME), server
    )
    bound = server.add_insecure_port(f"[::]:{port}")
    health_servicer.set(SERVICE_NAME, health_pb2.HealthCheckResponse.SERVING)
    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)
    log.info("provider=%s", provider.name)
    return server, bound


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s [llm-gateway] %(name)s: %(message)s",
    )
    port = int(os.environ.get("GRPC_PORT", "9087"))
    server, bound = build_server(port)
    server.start()
    log.info("gRPC server listening on port %d serving [%s]", bound, SERVICE_NAME)
    stop = threading.Event()

    def shutdown(signum, _frame):
        log.info("signal %s: shutting down", signum)
        server.stop(grace=10)
        stop.set()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    stop.wait()


if __name__ == "__main__":
    main()

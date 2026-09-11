"""Entry point: serve OptimizationService with health + reflection on GRPC_PORT (default 9083)."""

from __future__ import annotations

import logging
import os
import signal
import threading
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection

from travelos.optimization.v1 import optimization_pb2, optimization_pb2_grpc
from travelos_optimization.service import OptimizationService

log = logging.getLogger("travelos.optimization")

SERVICE_NAME = optimization_pb2.DESCRIPTOR.services_by_name["OptimizationService"].full_name


def build_server(port: int, max_workers: int = 8) -> tuple[grpc.Server, int]:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=max_workers))
    optimization_pb2_grpc.add_OptimizationServiceServicer_to_server(OptimizationService(), server)
    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    reflection.enable_server_reflection(
        (SERVICE_NAME, health.SERVICE_NAME, reflection.SERVICE_NAME), server
    )
    bound = server.add_insecure_port(f"[::]:{port}")
    health_servicer.set(SERVICE_NAME, health_pb2.HealthCheckResponse.SERVING)
    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)
    return server, bound


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s [optimization] %(name)s: %(message)s",
    )
    port = int(os.environ.get("GRPC_PORT", "9083"))
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

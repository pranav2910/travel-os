# syntax=docker/dockerfile:1.7
# One image recipe for the Python gRPC services (intelligence/<MODULE>).
#   docker build -f docker/python.Dockerfile --build-arg MODULE=llm-gateway --build-arg ENTRY=travelos_llm_gateway.server -t <image> .
# Stubs are generated from contracts/protobuf at build time, so the image can never drift from the
# contracts it was built with. Runtime has no compiler, no dev tools, no root.
ARG PYTHON=python:3.12-slim
ARG UV=ghcr.io/astral-sh/uv:0.11.21

FROM ${UV} AS uv
FROM ${PYTHON} AS build
ARG MODULE
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_PROJECT_ENVIRONMENT=/opt/venv UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy
WORKDIR /workspace/intelligence/${MODULE}
COPY contracts/protobuf/src/main/proto /workspace/contracts/protobuf/src/main/proto
COPY intelligence/${MODULE}/pyproject.toml intelligence/${MODULE}/uv.lock intelligence/${MODULE}/.python-version ./
RUN uv sync --frozen --no-install-project
COPY intelligence/${MODULE}/ ./
RUN uv run --frozen python scripts/gen_proto.py && uv sync --frozen --no-dev

FROM ${PYTHON}
ARG MODULE
ARG ENTRY
LABEL org.opencontainers.image.source="https://github.com/pranav2910/travel-os"
RUN groupadd -r -g 10001 travelos && useradd -r -u 10001 -g travelos -s /usr/sbin/nologin travelos
WORKDIR /workspace/intelligence/${MODULE}
COPY --from=build --chown=10001:10001 /opt/venv /opt/venv
COPY --from=build --chown=10001:10001 /workspace/intelligence/${MODULE}/src ./src
COPY --from=build --chown=10001:10001 /workspace/intelligence/${MODULE}/pyproject.toml ./pyproject.toml
ENV PATH="/opt/venv/bin:$PATH" PYTHONUNBUFFERED=1 PY_ENTRY=${ENTRY}
USER 10001
CMD ["sh", "-c", "exec python -m $PY_ENTRY"]

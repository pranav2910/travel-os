#!/usr/bin/env bash
# Builds (and optionally pushes) every runnable image. One code path for `make images` and CI.
#   scripts/build-images.sh [TAG] [--push]
# Jars must exist (make jars). Images: ghcr.io/pranav2910/travel-os/<name>:<TAG>
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-local}"; PUSH="${2:-}"
REGISTRY="${REGISTRY:-ghcr.io/pranav2910/travel-os}"
VERSION="0.1.0-SNAPSHOT"

build() { # name dockerfile args...
  local name="$1"; shift
  echo "== $REGISTRY/$name:$TAG"
  docker build -q -f "$1" "${@:2}" -t "$REGISTRY/$name:$TAG" . >/dev/null
  if [ "$PUSH" = "--push" ]; then
    # A failed push must fail the run: an image CI "published" but nobody can pull is worse than none.
    docker push -q "$REGISTRY/$name:$TAG" >/dev/null
    echo "   pushed $(docker inspect --format '{{index .RepoDigests 0}}' "$REGISTRY/$name:$TAG" 2>/dev/null || echo '(digest unavailable)')"
  fi
}

for svc in travel-core policy supplier-gateway order audit disruption enterprise-context learning; do
  build "$svc" docker/java.Dockerfile --build-arg "JAR=services/$svc/build/libs/$svc-$VERSION.jar"
done
build trip-planning docker/java.Dockerfile --build-arg "JAR=workflows/trip-planning/build/libs/trip-planning-$VERSION.jar"
build optimization docker/python.Dockerfile --build-arg MODULE=optimization --build-arg ENTRY=travelos_optimization.server
build llm-gateway docker/python.Dockerfile --build-arg MODULE=llm-gateway --build-arg ENTRY=travelos_llm_gateway.server
# The web edge (React app + nginx proxy); built inside Docker, so no Node toolchain is needed here.
build web docker/web.Dockerfile

echo; docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | grep -E "^$REGISTRY/" | grep -E "\s$TAG\s"

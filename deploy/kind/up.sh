#!/usr/bin/env bash
# Creates the kind cluster (3 zones), a local image registry, Calico (NetworkPolicy enforcement)
# and metrics-server (HPA), and the two namespaces. Idempotent.
set -euo pipefail
cd "$(dirname "$0")"
CALICO_VERSION="${CALICO_VERSION:-v3.32.2}"
METRICS_SERVER_VERSION="${METRICS_SERVER_VERSION:-v0.9.0}"
REG_NAME=kind-registry; REG_PORT=5001

if [ "$(docker inspect -f '{{.State.Running}}' "$REG_NAME" 2>/dev/null || true)" != 'true' ]; then
  docker run -d --restart=always -p "127.0.0.1:${REG_PORT}:5000" --network bridge --name "$REG_NAME" registry:2 >/dev/null
  echo "registry: started localhost:${REG_PORT}"
fi

if ! kind get clusters 2>/dev/null | grep -qx travelos; then
  kind create cluster --config kind-config.yaml --wait 120s
else
  echo "cluster: travelos already exists"
fi

# Nodes resolve localhost:5001 to the registry container (kind's documented local-registry recipe).
for node in $(kind get nodes --name travelos); do
  docker exec "$node" mkdir -p "/etc/containerd/certs.d/localhost:${REG_PORT}"
  docker exec -i "$node" cp /dev/stdin "/etc/containerd/certs.d/localhost:${REG_PORT}/hosts.toml" <<TOML
[host."http://${REG_NAME}:5000"]
TOML
done
if [ "$(docker inspect -f='{{json .NetworkSettings.Networks.kind}}' "$REG_NAME")" = 'null' ]; then
  docker network connect kind "$REG_NAME"
fi
kubectl apply -f - <<YAML
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${REG_PORT}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
YAML

echo "cni: calico ${CALICO_VERSION}"
kubectl apply -f "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/calico.yaml" >/dev/null
kubectl -n kube-system rollout status daemonset/calico-node --timeout=300s
kubectl wait --for=condition=Ready nodes --all --timeout=180s >/dev/null

echo "metrics-server ${METRICS_SERVER_VERSION}"
kubectl apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/${METRICS_SERVER_VERSION}/components.yaml" >/dev/null
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null
kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s

for ns in travelos-infra travelos; do
  kubectl get namespace "$ns" >/dev/null 2>&1 || kubectl create namespace "$ns" >/dev/null
done
kubectl get nodes -L topology.kubernetes.io/zone

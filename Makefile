# Developer entry points. Everything here is also runnable directly (gradlew / docker compose);
# the Makefile just remembers the flags for you.
SHELL := /bin/bash

# Homebrew's openjdk@21 is not registered with /usr/libexec/java_home unless symlinked, so fall back to its keg path.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
export JAVA_HOME

GRADLE  := ./gradlew
COMPOSE := docker compose -f platform/local/docker-compose.yml
STACK   := docker compose -f platform/local/docker-compose.yml -f platform/local/docker-compose.app.yml
JARS    := travel-core policy supplier-gateway order audit disruption enterprise-context learning

.PHONY: help up down nuke ps logs build test check fmt clean run run-worker seed-policy run-optimization run-llm-gateway jars images web-install web-dev web-check web-e2e web-e2e-kind stack-up stack-down stack-nuke stack-ps stack-logs stack-e2e stack-e2e2 stack-e2e3 stack-e2e4 stack-e2e5 kind-up kind-deploy kind-e2e kind-e2e2 kind-e2e3 kind-e2e4 kind-e2e5 kind-chaos kind-chaos2 kind-chaos3 kind-chaos4 kind-chaos5 kind-rollback-demo kind-down helm-lint tf-check

help: ## list targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## start the local platform (postgres, redis, kafka, temporal, keycloak), wait until healthy, create topics
	$(COMPOSE) up -d --wait
	$(COMPOSE) run --rm kafka-init

down: ## stop the local platform (data kept)
	$(COMPOSE) down

nuke: ## stop the local platform and delete all data volumes
	$(COMPOSE) down -v --remove-orphans

ps: ## show local platform status
	$(COMPOSE) ps

logs: ## tail local platform logs (SVC=kafka to filter)
	$(COMPOSE) logs -f $(SVC)

build: ## compile everything (no tests)
	$(GRADLE) assemble

test: ## run all tests
	$(GRADLE) test

check: ## full verification: compile, format check, tests
	$(GRADLE) check

fmt: ## apply code formatting
	$(GRADLE) spotlessApply

run: ## run one service against the local platform: make run SVC=travel-core
	TRACING_EXPORT_ENABLED=true $(GRADLE) :services:$(SVC):bootRun

run-worker: ## run the Temporal trip-planning worker
	TRACING_EXPORT_ENABLED=true $(GRADLE) :workflows:trip-planning:bootRun

run-optimization: ## run the Python optimization service (gRPC :9083)
	cd intelligence/optimization && uv sync --frozen && uv run --frozen python scripts/gen_proto.py && OTEL_EXPORTER_OTLP_ENDPOINT=$${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317} uv run --frozen python -m travelos_optimization.server

run-llm-gateway: ## run the LLM gateway (gRPC :9087). LLM_PROVIDER=fake for offline; anthropic when ANTHROPIC_API_KEY is set
	cd intelligence/llm-gateway && uv sync --frozen && uv run --frozen python scripts/gen_proto.py && OTEL_EXPORTER_OTLP_ENDPOINT=$${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317} uv run --frozen python -m travelos_llm_gateway.server

jars: ## build every runnable jar (8 services + the worker)
	$(GRADLE) $(foreach s,$(JARS),:services:$(s):bootJar) :workflows:trip-planning:bootJar -q

images: jars ## build all 12 container images (incl. the web edge) as ghcr.io/pranav2910/travel-os/<name>:local
	scripts/build-images.sh local

stack-up: ## run the WHOLE platform in Docker: infra + 9 services + the web app on :8080, wait healthy, create topics (needs `make images`)
	$(STACK) up -d --wait
	$(COMPOSE) run --rm kafka-init

stack-down: ## stop the Docker stack (data kept)
	$(STACK) down

stack-nuke: ## stop the Docker stack and delete all data volumes
	$(STACK) down -v --remove-orphans

stack-ps: ## status of the Docker stack
	$(STACK) ps

stack-logs: ## tail Docker stack logs (SVC=trip-planning to filter)
	$(STACK) logs -f $(SVC)

stack-e2e2: ## run the Slice 2 script against the Docker stack
	bash scripts/e2e-slice2.sh
stack-e2e3: ## run the Slice 3 script against the Docker stack
	bash scripts/e2e-slice3.sh
stack-e2e4: ## run the Slice 4 script (demand detection) against the Docker stack
	bash scripts/e2e-slice4.sh
stack-e2e5: ## run the Slice 5 script (learning from outcomes) against the Docker stack
	bash scripts/e2e-slice5.sh
web-install: ## install the web app's pinned dependencies (npm ci)
	cd web && npm ci --no-audit --no-fund
web-dev: ## run the web app with Vite on :5173, proxying /api to the services on their host ports
	cd web && npm run dev
web-check: ## lint, type-check, unit-test and build the web app
	cd web && npm run check
web-e2e: ## browser E2E (Playwright) against the Docker stack: web :8080, Keycloak :8180
	cd web && E2E_BASE_URL=http://localhost:8080 E2E_KEYCLOAK_URL=http://localhost:8180 E2E_SUPPLIER_URL=http://localhost:8084 npx playwright test
web-e2e-kind: ## browser E2E (Playwright) against the kind cluster: web :18080, Keycloak :18180
	cd web && E2E_BASE_URL=http://localhost:18080 E2E_KEYCLOAK_URL=http://localhost:18180 E2E_SUPPLIER_URL=http://localhost:18084 \
	  E2E_WEBHOOK_SECRET=$$(grep '^SANDBOX_AIR_WEBHOOK_SECRET=' ../deploy/kind/.secrets.env | cut -d= -f2) npx playwright test

stack-e2e: ## run the live end-to-end script against the Docker stack
	bash scripts/e2e-slice1.sh

# ---- Kubernetes (kind as the EKS stand-in) ----
kind-up: ## create the kind cluster (3 zones, Calico, metrics-server, local registry)
	deploy/kind/up.sh

kind-deploy: ## build :<sha> images, push to the local registry, deploy infra + services with Helm
	deploy/kind/deploy.sh

kind-e2e: ## run the Slice 1 end-to-end script against the kind cluster
	E2E_BACKEND=kind bash scripts/e2e-slice1.sh

kind-chaos: ## kill pods mid-flight and prove no duplicate orders, no lost state, enforced NetworkPolicies
	bash scripts/chaos-kind.sh

kind-e2e2: ## run the Slice 2 disruption-recovery script against the kind cluster
	E2E_BACKEND=kind bash scripts/e2e-slice2.sh

kind-chaos2: ## kill the recovery worker and the order service during ChangeOrder; prove one logical recovery
	bash scripts/chaos-slice2-kind.sh
kind-e2e3: ## run the Slice 3 itinerary script (hotels, ground, multi-city) against the kind cluster
	E2E_BACKEND=kind bash scripts/e2e-slice3.sh
kind-chaos3: ## hold a 7-component booking and its recovery at proven points, kill the worker; prove one booking each
	bash scripts/chaos-slice3-kind.sh
kind-e2e4: ## run the Slice 4 demand-detection script (calendar/CRM/HRIS/expense -> candidate -> trip) against the kind cluster
	E2E_BACKEND=kind bash scripts/e2e-slice4.sh
kind-chaos4: ## hold a connector sync at proven points (source outage, rate limit, context service gone, worker killed); prove one candidate, one trip
	bash scripts/chaos-slice4-kind.sh
kind-e2e5: ## run the Slice 5 learning script (outcomes -> profile -> shadow/active ranking, safeguards, rollback) against the kind cluster
	E2E_BACKEND=kind bash scripts/e2e-slice5.sh
kind-chaos5: ## learning service and worker killed during planning and a profile build; consumer restarts; pinned inputs; prove baseline fallback and exactly-once outcomes
	bash scripts/chaos-slice5-kind.sh

kind-rollback-demo: ## deploy a stand-in "next" release then roll back to the previous revision
	bash scripts/rollback-kind.sh

kind-down: ## delete the kind cluster and the local registry
	deploy/kind/down.sh

helm-lint: ## lint + render + schema-check the charts for kind and EKS
	helm dependency update deploy/helm/travelos >/dev/null
	helm lint deploy/helm/travelos-service --set name=x --set global.image.tag=t
	helm lint deploy/helm/travelos -f deploy/helm/travelos/values-kind.yaml --set global.image.tag=t
	helm lint deploy/helm/travelos-infra
	helm template travelos deploy/helm/travelos -n travelos -f deploy/helm/travelos/values-kind.yaml --set global.image.tag=t | kubeconform -strict -summary -skip ExternalSecret
	helm template travelos deploy/helm/travelos -n travelos -f deploy/helm/travelos/values-eks.yaml --set global.image.tag=t | kubeconform -strict -summary -skip ExternalSecret
	helm template travelos-infra deploy/helm/travelos-infra -n travelos-infra | kubeconform -strict -summary

TF_ROOTS := bootstrap environments/dev environments/staging environments/prod
tf-check: ## terraform fmt/validate + tflint + trivy for every root (no AWS credentials needed, nothing applied)
	cd infrastructure/terraform && terraform fmt -check -recursive -diff
	@for d in $(TF_ROOTS); do echo "== validate $$d"; (cd infrastructure/terraform/$$d && terraform init -backend=false -input=false >/dev/null && terraform validate) || exit 1; done
	cd infrastructure/terraform && tflint --init >/dev/null
	@for d in $(TF_ROOTS); do echo "== tflint $$d"; (cd infrastructure/terraform && tflint --config "$$(pwd)/.tflint.hcl" --chdir=$$d --call-module-type=all) || exit 1; done
	cd infrastructure/terraform && trivy config --severity LOW,MEDIUM,HIGH,CRITICAL --exit-code 1 -q .

seed-policy: ## publish the seed travel policy for tenant acme (policy service must be running on :8082)
	@TOKEN=$$(curl -sf -X POST http://localhost:8180/realms/travelos/protocol/openid-connect/token -d client_id=travelos-dev-cli -d grant_type=password -d username=carol -d password=password | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'); \
	 python3 -c 'import json,sys; print(json.dumps({"document": json.load(open("platform/local/seed/policies/acme-us-standard.json")), "note": "seeded by make seed-policy"}))' \
	 | curl -s -X POST http://localhost:8082/api/v1/policies -H "Authorization: Bearer $$TOKEN" -H 'Content-Type: application/json' -d @- \
	 | python3 -c 'import sys,json; d=json.load(sys.stdin); print("policy", d.get("policyId"), "version", d.get("version"), d.get("code",""))'

clean: ## remove build outputs
	$(GRADLE) clean

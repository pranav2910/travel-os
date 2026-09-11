# Developer entry points. Everything here is also runnable directly (gradlew / docker compose);
# the Makefile just remembers the flags for you.
SHELL := /bin/bash

# Homebrew's openjdk@21 is not registered with /usr/libexec/java_home unless symlinked, so fall back to its keg path.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
export JAVA_HOME

GRADLE  := ./gradlew
COMPOSE := docker compose -f platform/local/docker-compose.yml

.PHONY: help up down nuke ps logs build test check fmt clean run seed-policy run-optimization

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
	$(GRADLE) :services:$(SVC):bootRun

run-optimization: ## run the Python optimization service (gRPC :9083)
	cd intelligence/optimization && uv sync --frozen && uv run --frozen python scripts/gen_proto.py && uv run --frozen python -m travelos_optimization.server

seed-policy: ## publish the seed travel policy for tenant acme (policy service must be running on :8082)
	@TOKEN=$$(curl -sf -X POST http://localhost:8180/realms/travelos/protocol/openid-connect/token -d client_id=travelos-dev-cli -d grant_type=password -d username=carol -d password=password | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'); \
	 python3 -c 'import json,sys; print(json.dumps({"document": json.load(open("platform/local/seed/policies/acme-us-standard.json")), "note": "seeded by make seed-policy"}))' \
	 | curl -s -X POST http://localhost:8082/api/v1/policies -H "Authorization: Bearer $$TOKEN" -H 'Content-Type: application/json' -d @- \
	 | python3 -c 'import sys,json; d=json.load(sys.stdin); print("policy", d.get("policyId"), "version", d.get("version"), d.get("code",""))'

clean: ## remove build outputs
	$(GRADLE) clean

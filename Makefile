# Developer entry points. Everything here is also runnable directly (gradlew / docker compose);
# the Makefile just remembers the flags for you.
.RECIPEPREFIX := >
SHELL := /bin/bash

# Homebrew's openjdk@21 is not registered with /usr/libexec/java_home unless symlinked, so fall back to its keg path.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
export JAVA_HOME

GRADLE  := ./gradlew
COMPOSE := docker compose -f platform/local/docker-compose.yml

.PHONY: help up down nuke ps logs build test check fmt clean

help: ## list targets
> @grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## start the local platform (postgres, redis, kafka, temporal, keycloak), wait until healthy, create topics
> $(COMPOSE) up -d --wait
> $(COMPOSE) run --rm kafka-init

down: ## stop the local platform (data kept)
> $(COMPOSE) down

nuke: ## stop the local platform and delete all data volumes
> $(COMPOSE) down -v --remove-orphans

ps: ## show local platform status
> $(COMPOSE) ps

logs: ## tail local platform logs (SVC=kafka to filter)
> $(COMPOSE) logs -f $(SVC)

build: ## compile everything (no tests)
> $(GRADLE) assemble

test: ## run all tests
> $(GRADLE) test

check: ## full verification: compile, format check, tests
> $(GRADLE) check

fmt: ## apply code formatting
> $(GRADLE) spotlessApply

clean: ## remove build outputs
> $(GRADLE) clean

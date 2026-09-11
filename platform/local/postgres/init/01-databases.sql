-- Runs once, on first start of the postgres volume (docker-entrypoint-initdb.d).
--
-- One database + one login role per service. A service's credentials physically cannot reach another
-- service's tables: "no service writes another service's database" is enforced, not hoped for.
-- The superuser (travelos) exists for humans and migrations tooling only.

-- Temporal (owned by the platform, not by any business service)
CREATE DATABASE temporal;
CREATE DATABASE temporal_visibility;

CREATE ROLE enterprise_context_app LOGIN PASSWORD 'enterprise_context-dev';
CREATE DATABASE enterprise_context OWNER enterprise_context_app;
REVOKE CONNECT ON DATABASE enterprise_context FROM PUBLIC;

CREATE ROLE travel_core_app LOGIN PASSWORD 'travel_core-dev';
CREATE DATABASE travel_core OWNER travel_core_app;
REVOKE CONNECT ON DATABASE travel_core FROM PUBLIC;

CREATE ROLE policy_app LOGIN PASSWORD 'policy-dev';
CREATE DATABASE policy OWNER policy_app;
REVOKE CONNECT ON DATABASE policy FROM PUBLIC;

CREATE ROLE approval_app LOGIN PASSWORD 'approval-dev';
CREATE DATABASE approval OWNER approval_app;
REVOKE CONNECT ON DATABASE approval FROM PUBLIC;

CREATE ROLE supplier_gateway_app LOGIN PASSWORD 'supplier_gateway-dev';
CREATE DATABASE supplier_gateway OWNER supplier_gateway_app;
REVOKE CONNECT ON DATABASE supplier_gateway FROM PUBLIC;

CREATE ROLE orders_app LOGIN PASSWORD 'orders-dev';
CREATE DATABASE orders OWNER orders_app;
REVOKE CONNECT ON DATABASE orders FROM PUBLIC;

CREATE ROLE audit_app LOGIN PASSWORD 'audit-dev';
CREATE DATABASE audit OWNER audit_app;
REVOKE CONNECT ON DATABASE audit FROM PUBLIC;

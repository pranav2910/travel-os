# Kubernetes runbook: kind today, EKS next

The same Helm charts deploy the platform to a local `kind` cluster and to EKS. Only the values file
and the secret source differ. Nothing in Git contains a password, token or API key.

| | kind (local, the EKS stand-in) | EKS |
|---|---|---|
| Cluster | `deploy/kind/up.sh` (3 workers in zones a/b/c, Calico, metrics-server, local registry) | `infrastructure/terraform/environments/<env>` |
| Infra services | `deploy/helm/travelos-infra` (Postgres, Kafka, Temporal, Keycloak, Redis, OTel/Tempo/Grafana) | Aurora, MSK, ElastiCache (Terraform); Temporal + Keycloak stay in-cluster or move to Temporal Cloud / the enterprise IdP |
| App services | `deploy/helm/travelos` + `values-kind.yaml` | `deploy/helm/travelos` + `values-eks.yaml` |
| Images | `localhost:5001/travel-os/<svc>:<git-sha>` | `<account>.dkr.ecr.<region>.amazonaws.com/travel-os/<svc>:<git-sha>` (immutable tags) |
| Secrets | `deploy/kind/secrets.sh` writes random passwords to a git-ignored file and creates plain Secrets | External Secrets Operator syncs `travelos/<env>/<svc>/*` from Secrets Manager via IRSA |
| Ingress | NodePorts mapped to `localhost:1xxxx` | one ALB (`templates/ingress.yaml`, class `alb`) fronting the four public REST APIs; AWS Load Balancer Controller installed from `deploy/helm/addons/` with its IRSA role from Terraform |

## Local Kubernetes (kind)

Prerequisites: Docker Desktop with ~8 GB for containers, `kind`, `kubectl`, `helm` 4, `kubeconform`
(`brew install kind kubectl helm kubeconform`). Stop the compose stack first (`make stack-down`) so
the cluster has the memory, and do not run `./gradlew check` (six Testcontainers stacks) while the
cluster is up: at 8 GB the Docker VM starves and every pod restarts at once, which resets the
in-memory metrics and looks like an outage that never happened.

```bash
make kind-up            # cluster + Calico + metrics-server + registry; namespaces travelos-infra, travelos
make kind-deploy        # jars -> images:<git-sha> -> push to localhost:5001 -> helm infra -> helm app
make kind-e2e           # the full Slice 1 E2E flow against the cluster (E2E_BACKEND=kind)
make kind-chaos         # kill pods mid-workflow, prove one order per trip; prove NetworkPolicies bite
make kind-e2e2          # Slice 2: autonomous + approved disruption recovery, injection, duplicates, trace
make kind-chaos2        # Slice 2: order + optimizer outages parked at proven points, worker killed mid-ChangeOrder; one recovery
make kind-e2e3          # Slice 3: multi-city itineraries (hotels, ground), revalidation, compensation, connected recovery
make kind-chaos3        # Slice 3: a 7-component booking and its recovery held at proven points, worker killed; one booking each
make kind-e2e4          # Slice 4: calendar/CRM/HRIS/expense demand detection -> a governed trip; Slice 3 carry-overs
make kind-chaos4        # Slice 4: a connector sync under a source outage, a rate limit, the service and the worker killed; one candidate each
make kind-rollback-demo # deploy a stand-in "next" release, roll back with helm, verify
make kind-down
```

`make kind-deploy` is idempotent: it always deploys the images for the current `git rev-parse
--short=12 HEAD`, with `-dirty` appended while the tree has uncommitted changes (a tag must never
mean two different builds, and a Deployment whose image reference did not change keeps its old
pods). Every Deployment's image is `<registry>/<service>:<sha>`; `latest` is never used.

Host ports once deployed:

| Service | URL |
|---|---|
| travel-core | http://localhost:18081 (gRPC 19081) |
| policy | http://localhost:18082 |
| supplier-gateway | http://localhost:18084 |
| order | http://localhost:18085 |
| trip-planning (worker actuator) | http://localhost:18086 |
| audit | http://localhost:18088 |
| disruption | http://localhost:18089 (gRPC 9089 in-cluster) |
| enterprise-context | http://localhost:18090 (gRPC 9090 in-cluster) |
| optimization gRPC | localhost:19083 |
| llm-gateway gRPC | localhost:19087 |
| Keycloak | http://localhost:18180 (admin password in `deploy/kind/.secrets.env`) |
| Temporal UI | http://localhost:18233 |
| Grafana / Tempo | http://localhost:13000 / http://localhost:13200 |

Useful commands:

```bash
kubectl -n travelos get pods -o wide                       # one pod per service, spread over zones
kubectl -n travelos get hpa,pdb,networkpolicy
kubectl -n travelos logs deploy/trip-planning -f
helm -n travelos history travelos                          # every release with its chart + tag
kubectl -n travelos get deploy -o custom-columns=NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image
```

### What the charts enforce

* **Probes**: Java services use the Boot actuator `startup` (liveness group, generous failure
  threshold for JVM start), `liveness` and `readiness` groups; Python services use gRPC health
  probes on their port.
* **Resources**: every container has a memory request and a memory limit (limit ≈ 1.25× request so
  a burst does not immediately OOM) and a CPU request without a CPU limit (no throttling of
  latency-sensitive services). JVM heap is bounded with
  `-XX:MaxRAMPercentage` via `JAVA_TOOL_OPTIONS` so it fits the memory limit.
* **Availability**: HPA (CPU) per service, PDB per service, topology spread over
  `topology.kubernetes.io/zone` and `kubernetes.io/hostname`, pod anti-affinity (soft on kind,
  hard on EKS with 3 replicas), rolling updates with `maxUnavailable: 0`.
* **Least privilege**: one ServiceAccount per service, token not automounted (no service talks to
  the Kubernetes API), non-root uid 10001, read-only root filesystem, all capabilities dropped,
  `RuntimeDefault` seccomp, service links disabled.
* **NetworkPolicy**: the namespace is default-deny in both directions. Each service opens exactly
  the flows the architecture needs (HTTP from the ingress, gRPC from the callers that exist, egress
  to DNS, the data services and the gRPC servers it calls; llm-gateway alone may reach the internet
  on 443). `make kind-chaos` proves the policies are enforced, not just rendered.
* **Secrets**: `secretRef` per service; on EKS an `ExternalSecret` materialises it from Secrets
  Manager. Values files only ever contain the *names* of secrets.

## Rollback

Releases are Helm revisions whose only variable is the image tag (a git SHA). Rolling back is
redeploying the previous known-good SHA; it is fast because the images already exist.

```bash
helm -n travelos history travelos                    # find the last good revision (DEPLOYED before the bad one)
helm -n travelos rollback travelos <revision> --wait  # every Deployment returns to that revision's tag
# or, explicitly by SHA:
helm upgrade travelos deploy/helm/travelos -n travelos -f deploy/helm/travelos/values-<env>.yaml \
  --set global.image.tag=<previous-good-sha> --wait
```

With Argo CD (`deploy/argocd/application.yaml`) the image tag lives in Git: revert the commit that
bumped `global.image.tag` (Argo syncs the revert), or `argocd app rollback travelos-<env>` to a
previous synced revision, then revert in Git so the desired state matches.

Rollback safety: the database migrations are additive (Flyway, never destructive within a slice),
outbox and Temporal state are versioned by the workflow type, so an older image reads state written
by a newer one. Temporal workflow code changes must stay replay-compatible (`Workflow.getVersion`
around any changed step) before a rollback across them is safe; in-flight workflows started by the
newer image are otherwise failed with a non-deterministic-history error and must be reset from the
Temporal UI.

## EKS (when AWS credentials are available)

1. **Bootstrap state** (once per account): `cd infrastructure/terraform/bootstrap && terraform init &&
   terraform apply -var aws_region=<region>`; copy the bucket name into
   `environments/<env>/backend.hcl` (from `backend.hcl.example`, git-ignored).
2. **Provision**: `cd environments/dev && terraform init -backend-config=backend.hcl && terraform plan
   -out=dev.tfplan && terraform apply dev.tfplan`. Creates the VPC (3 AZs, public/private/database
   subnets, NAT, flow logs), EKS with three managed node groups (general/workflow/ai), Aurora
   PostgreSQL Serverless v2, MSK (TLS + IAM), optional ElastiCache, KMS keys per purpose, Secrets
   Manager secrets per service, S3 audit archive, ECR repositories, and IRSA roles.
3. **Cluster access**: `aws eks update-kubeconfig --name $(terraform output -raw cluster_name)`.
4. **Add-ons**: install External Secrets Operator (`helm install external-secrets ...`, its
   ServiceAccount annotated with `terraform output irsa_role_arns` → `external-secrets`) and a
   `ClusterSecretStore` named `aws-secrets-manager`; install metrics-server and the cluster
   autoscaler or Karpenter.
5. **Databases**: run the role bootstrap once against the Aurora writer using the master secret
   (`aurora_master_secret_arn`): `deploy/helm/travelos-infra/templates/postgres-roles.yaml` is the
   idempotent script (roles + databases) with the per-service passwords from `travelos/<env>/<svc>/db`;
   on kind the same script runs as a Helm hook after every upgrade.
6. **Images**: `REGISTRY=<ecr>/travel-os scripts/build-images.sh <git-sha> --push` after
   `aws ecr get-login-password | docker login`.
7. **Deploy**: fill `values-eks.yaml` endpoints from `terraform output` (or override with `--set`)
   and run `helm upgrade --install travelos deploy/helm/travelos -n travelos -f
   deploy/helm/travelos/values-eks.yaml --set global.image.registry=<ecr>/travel-os --set
   global.image.tag=<git-sha>`; or apply `deploy/argocd/application.yaml`.
8. **Verify**: `E2E_BACKEND=kind` with the host variables (`KC`, `CORE`, …) pointed at the
   cluster's ingress runs the same E2E suite.

Kafka security is one switch shared by every client, Java and Python: `KAFKA_AUTH=none` (the
stand-in broker on kind) or `KAFKA_AUTH=msk-iam` (`values-eks.yaml`): SASL_SSL with `AWS_MSK_IAM`,
tokens signed by each pod's IRSA role (`libs/spring-kafka-security`, `intelligence/optimization/…/events.py`).
Terraform grants producers `WriteData` on `travel.*` and consumers `ReadData` + their group, nothing
more. A private CA can be trusted with `KAFKA_SSL_TRUSTSTORE_LOCATION` (Java) / `KAFKA_SSL_CA_LOCATION`
(Python); MSK's Amazon-issued certificates need neither.

Ingress: install the AWS Load Balancer Controller (`deploy/helm/addons/aws-load-balancer-controller.values.yaml`,
role ARN from `terraform output irsa_role_arns`), put an ACM certificate ARN and host into the
`ingress` block of `values-eks.yaml`, and point DNS at the ALB the controller creates. Narrow the
`awsEgress`/`httpFromVpc` CIDRs in that file to `terraform output vpc_cidr`.

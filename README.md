# Insurance Microservices Platform

Spring Boot insurance platform simulation with four services:

- `auth-service`: MySQL-backed authentication, refresh tokens, and RBAC for `POLICYHOLDER`, `AGENT`, and `ADMIN`
- `policy-service`: PostgreSQL-backed policy issuance, renewal, and lapse workflows with Kafka publishing
- `claims-service`: Redis-backed claims workflow with Kafka-driven policy projection sync
- `api-gateway`: Spring Cloud Gateway edge service with JWT validation and Redis-backed rate limiting

## Stack

- Java 17
- Spring Boot 3.5.9
- Spring Security + JWT
- Spring Cloud Gateway
- Spring Data JPA + MySQL/PostgreSQL
- Spring Data Redis
- Spring Kafka
- Docker Compose
- Kubernetes manifests
- GitHub Actions

## Local Run

Start the full stack:

```bash
docker compose up --build
```

The public entrypoint is the gateway on `http://localhost:8080`.

Internal service ports:

- `auth-service`: `8081`
- `policy-service`: `8082`
- `claims-service`: `8083`

Infrastructure ports:

- MySQL: `3306`
- PostgreSQL: `5432`
- Redis: `6379`
- Kafka: `9092`

## API Surface

Gateway routes:

- `/api/auth/**`
- `/api/policies/**`
- `/api/claims/**`

Auth endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/auth/me`
- `POST /api/auth/admin/users`

Policy endpoints:

- `POST /api/policies`
- `POST /api/policies/{policyId}/renew`
- `POST /api/policies/{policyId}/lapse`
- `GET /api/policies`
- `GET /api/policies/{policyId}`

Claim endpoints:

- `POST /api/claims`
- `POST /api/claims/{claimId}/verify`
- `POST /api/claims/{claimId}/approve`
- `POST /api/claims/{claimId}/reject`
- `GET /api/claims`
- `GET /api/claims/{claimId}`

## Roles

- `POLICYHOLDER`: register, log in, view own policies, submit and view own claims
- `AGENT`: issue/renew/lapse assigned policies and verify assigned claims
- `ADMIN`: create users, inspect all data, and approve/reject claims

## Build And Test

Run all module tests:

```bash
./mvnw test
```

Run the end-to-end smoke flow against a running Compose stack:

```bash
./scripts/smoke-test.sh
```

## Kubernetes

Kubernetes manifests are under `k8s/` and mirror the Compose topology:

- shared infra: MySQL, PostgreSQL, Redis, Kafka
- services: auth, policy, claims, gateway
- ingress: `k8s/ingress/platform-ingress.yaml`

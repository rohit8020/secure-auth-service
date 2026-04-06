# Insurance Microservices Platform

Spring Boot insurance platform simulation with four services:

- `auth-service`: MySQL-backed authentication with RS256 token issuance, JWKS exposure, hashed refresh tokens, and OAuth2 client credentials for machine-to-machine access
- `policy-service`: PostgreSQL-backed policy issuance, idempotency protection, Flyway migrations, and transactional outbox publishing to Kafka
- `claims-service`: PostgreSQL-backed claims workflow with Redis projection cache, policy projection fallback, idempotency protection, Flyway migrations, and transactional outbox publishing
- `api-gateway`: Spring Cloud Gateway edge service with JWKS-based JWT validation, Redis-backed rate limiting, and Prometheus metrics

## Stack

- Java 17
- Spring Boot 3.5.9
- Spring Security + JWT
- OAuth2 client credentials + JWKS
- Spring Cloud Gateway
- Spring Data JPA + MySQL/PostgreSQL
- Spring Data Redis
- Spring Kafka
- Flyway
- Micrometer + Prometheus + Zipkin
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
- Zipkin: `9411`
- Prometheus: `9090`
- Grafana: `3000`

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

- `POST /api/policies` with `Idempotency-Key`
- `POST /api/policies/{policyId}/renew`
- `POST /api/policies/{policyId}/lapse`
- `GET /api/policies`
- `GET /api/policies/{policyId}`

Claim endpoints:

- `POST /api/claims` with `Idempotency-Key`
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

The smoke flow now exercises:

- RS256/JWKS token validation through the gateway
- policy issuance with idempotency
- claim submission replay with the same `Idempotency-Key`
- policy verification and claim approval workflow

## Kubernetes

Kubernetes manifests are under `k8s/` and mirror the Compose topology:

- shared infra: MySQL, PostgreSQL, Redis, Kafka
- services: auth, policy, claims, gateway
- ingress: `k8s/ingress/platform-ingress.yaml`

## Production-Oriented Features

- transactional outbox in `policy-service` and `claims-service`
- Kafka retry and DLQ wiring
- JWKS-based RS256 validation across gateway and services
- OAuth2 client credentials for `claims-service -> policy-service`
- hashed and rotated refresh tokens
- paginated list APIs with stable `PagedResponse` contracts
- Prometheus metrics, Zipkin tracing, and JSON structured logs

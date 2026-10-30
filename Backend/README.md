# Smart Weather & Air Quality Monitoring Platform

A production-ready, highly resilient, event-driven IoT sensor monitoring platform. Built to ingest telemetry and heartbeats from physical hardware over AWS IoT Core, detecting environmental anomalies via a PyTorch Autoencoder stream, routing events via RabbitMQ, preventing DDoS via native Gateway Token Bucket Rate Limiting, and exposing data for real-time visualization via React.

## 🌟 Key Capabilities
*   **Fully Asynchronous IoT Core Ingestion**: Device payload flow dynamically moves from WiFi → MQTT → AWS IoT Core → AWS SQS → `iot-ingestion-service` → RabbitMQ.
*   **Continuous Analytics AI Pipeline**: Replaced generic outlier models with a state-of-the-art **Hybrid Intelligence System**. 
    *   **Chronos-2**: Zero-shot foundational models calculating precise Forecast Residuals.
    *   **Isolation Forest**: Multi-dimensional legacy feature separation mapping (scikit-learn).
    *   **Explanation Engine**: Resolves decisions into explicit human-readable `reasons` explaining *why* something triggered.
*   **Production-Grade Architecture**: Zero downtime CI/CD implementations via GitHub Actions (Main+PR workflows), hardened Docker multistage footprints, native Hikari connection pooling, Spring production `profiles`, and token rate limiting preventing scaling cliffs.
*   **Device Shadow (Digital Twin Replica)**: Devices possess true real-time AWS shadow clones monitoring firmware variants, GPS loci, remaining battery charge metrics, and connection heartbeats decoupled from actual environmental logging rules.

## 🏗️ Technical Architecture
### Ingestion Layer & Gateway
1.  **API Gateway**: `8443` | Spring Cloud Gateway. Includes global JWT validation (`X-User-Id` injection decorators) + advanced ConcurrentHashMap rate limiters scaling dynamically by traffic contexts (Auth vs Reports).
2.  **Auth Service**: `8083` | PostgreSQL | Spring Security, RegEx complex auth, and JSON Web Tokens.
3.  **IoT Ingestion Service**: `8086` | Listens indefinitely to localstack SQS Queues. Defends against idempotency (replay attacks) natively and proxies safely to RabbitMQ events.

### Async Core Fleet
4.  **Device Service**: `8080` | PostgreSQL | Tracks fleets, certificates, battery strengths, OTA (S3 metadata), and device online/offline transitions.
5.  **Sensor Service**: `8081` | PostgreSQL | Evaluates incoming RabbitMQ queues against threshold configs, executes Circuit-Breaker REST queries towards the AI subsystem, and archives reads.
6.  **Analytics Service**: `8084` | PostgreSQL | Re-streams RabbitMQ readings into time-series style dimensional storage, calculating daily means, variations, and location heatmaps.
7.  **Alert Service**: `8085` | PostgreSQL | Constantly maps Sensor failures, AI anomalies, offline tags against Custom Alert Rules (Dynamic Notification System).
8.  **AI Service**: `8000` | FastAPI | Scikit-Learn + Amazon Chronos-2. Computes concurrent Deterministic Safety rules heavily combined against persistent isolation boundaries. Model lifecycle engines cache states resolving memory exhaustion footprints completely.

## 🚀 Running Locally (Compose)
Infrastructure is bootstrapped via `docker-compose`. Ensure `.env` is initialized.
```bash
cp .env.example .env
docker compose up --build -d
```
Localstack initializes automatic queue provisioning script upon startup. Access APIs directly across `localhost:8443`.

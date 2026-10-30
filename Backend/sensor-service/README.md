# Sensor Service — Smart Weather & Air Quality Monitoring Platform

Production-grade microservice for ingesting, querying, and managing environmental telemetry data from IoT weather nodes.

---

## Technical Overview

* **Java Version:** 21 (LTS)
* **Framework:** Spring Boot 3.4.1
* **Database:** PostgreSQL 16
* **Build System:** Apache Maven 3.9+
* **Documentation:** OpenAPI 3.0 / Swagger UI (`springdoc-openapi`)
* **Testing:** JUnit 5, Mockito, Spring Boot Test (`MockMvc`)

---

## Folder Structure

```
sensor-service/
├── Dockerfile                                 # Multi-stage production container build
├── pom.xml                                    # Dependencies & Maven build configuration
├── postman/
│   └── SensorService.postman_collection.json  # Importable Postman collection
├── README.md                                  # Service documentation and setup instructions
└── src/
    ├── main/
    │   ├── java/com/weatherplatform/sensorservice/
    │   │   ├── SensorServiceApplication.java   # Spring Boot entry point
    │   │   ├── config/
    │   │   │   └── OpenApiConfig.java          # Swagger/OpenAPI documentation bean
    │   │   ├── controller/
    │   │   │   └── SensorDataController.java   # REST Controller exposing API endpoints
    │   │   ├── dto/
    │   │   │   ├── PagedSensorDataResponse.java# Paginated wrapper response DTO
    │   │   │   ├── SensorDataRequest.java      # Validated inbound payload DTO
    │   │   │   └── SensorDataResponse.java     # Outbound telemetry response DTO
    │   │   ├── entity/
    │   │   │   └── SensorData.java             # JPA Entity mapping to PostgreSQL
    │   │   ├── exception/
    │   │   │   ├── ErrorResponse.java          # Standardized error payload
    │   │   │   ├── GlobalExceptionHandler.java # RestControllerAdvice exception interceptor
    │   │   │   ├── InvalidDateRangeException.java # Custom 400 exception for date ranges
    │   │   │   └── ResourceNotFoundException.java # Custom 404 exception
    │   │   ├── mapper/
    │   │   │   └── SensorDataMapper.java       # Decoupled entity <-> DTO converter
    │   │   ├── repository/
    │   │   │   └── SensorDataRepository.java   # Spring Data JPA data access layer
    │   │   └── service/
    │   │       ├── SensorDataService.java      # Service interface (SOLID DIP)
    │   │       └── SensorDataServiceImpl.java  # Business logic & transaction boundaries
    │   └── resources/
    │       ├── application.yml                 # Database & service application configuration
    │       └── openapi/
    │           └── sensor-service-api.yaml     # Static OpenAPI 3.0 specification file
    └── test/
        └── java/com/weatherplatform/sensorservice/
            ├── controller/
            │   └── SensorDataControllerTest.java # MockMvc REST endpoint unit tests
            └── service/
                └── SensorDataServiceImplTest.java# Mockito unit tests for business logic
```

### Package Explanations

1. **`controller`**: Handles HTTP request mapping, parameter extraction, and status code responses. Keeps business logic zero.
2. **`service`**: Implements domain logic, validation checks, logging, and transactional integrity.
3. **`repository`**: Abstracts database interaction using Spring Data JPA method generation and JPQL queries.
4. **`entity`**: Represents database table mappings. Strictly encapsulated within service boundaries.
5. **`dto`**: Inbound/Outbound contract models. Prevents API consumers from accessing internal entity schema.
6. **`mapper`**: Converts Entities to DTOs and vice versa to keep service layer clean.
7. **`config`**: Holds bean definitions, such as OpenAPI Swagger setup.
8. **`exception`**: Centralized `@RestControllerAdvice` error translation into standard JSON bodies.

---

## API Endpoints Summary

| Method | Endpoint | Description | Status Codes |
|---|---|---|---|
| `POST` | `/api/sensors/data` | Ingest a new sensor reading | `201 Created`, `400 Bad Request` |
| `GET` | `/api/sensors/latest/{deviceUid}` | Retrieve latest telemetry reading for a device | `200 OK`, `404 Not Found` |
| `GET` | `/api/sensors/history/{deviceUid}` | Paginated history (`page`, `size`) | `200 OK` |
| `GET` | `/api/sensors/history/{deviceUid}?startDate=&endDate=` | Date-range filtered history | `200 OK`, `400 Bad Request` |

---

## Validation Bounds

* **Temperature:** `-50.00 °C` to `80.00 °C`
* **Humidity:** `0.00 %` to `100.00 %`
* **Air Quality (AQI):** `0` to `500`
* **Pressure:** `300.00 hPa` to `1200.00 hPa`
* **Rain:** `true` / `false` (`@NotNull`)
* **deviceUid:** Min 3, Max 100 characters

---

## Setup & Running Locally

### 1. Database Setup
Create PostgreSQL database:
```sql
CREATE DATABASE sensor_db;
```

### 2. Run Application
```bash
mvn clean spring-boot:run
```

### 3. Access Swagger UI
Open browser:
```
http://localhost:8081/swagger-ui.html
```

### 4. Run Unit Tests
```bash
mvn clean test
```

---

## Docker Build

```bash
docker build -t sensor-service:latest .
docker run -p 8081:8081 -e SENSOR_DB_URL=jdbc:postgresql://host.docker.internal:5432/sensor_db sensor-service:latest
```

# Database Design (PostgreSQL)

## Design Principles

- Normalized relational structure
- Service-owned data boundaries
- Strong constraints for data quality
- Indexes optimized for operational queries

## Table: `devices` (device-service)

- `id BIGSERIAL PRIMARY KEY`: internal surrogate key.
- `device_uid VARCHAR(100) UNIQUE NOT NULL`: external immutable identifier for cross-service correlation.
- `device_name VARCHAR(150) NOT NULL`: operator-friendly device label.
- `location VARCHAR(200) NOT NULL`: deployment location.
- `status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE'`: lifecycle state.
- `registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`: registration timestamp with timezone.

Constraints:
- `UNIQUE(device_uid)`
- `CHECK(status IN ('ACTIVE','INACTIVE','MAINTENANCE','OFFLINE'))`

Indexes:
- `idx_devices_status` on `status`

## Table: `sensor_data` (sensor-service)

- `id BIGSERIAL PRIMARY KEY`: internal key for records.
- `device_uid VARCHAR(100) NOT NULL`: correlates reading to device.
- `temperature DECIMAL(5,2) NOT NULL`: temperature reading in Celsius.
- `humidity DECIMAL(5,2) NOT NULL`: relative humidity percentage.
- `air_quality INTEGER NOT NULL`: AQI value.
- `pressure DECIMAL(7,2) NOT NULL`: atmospheric pressure.
- `rain BOOLEAN NOT NULL DEFAULT FALSE`: rain detection.
- `recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`: sensor capture time.
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`: ingestion timestamp.

Constraints:
- `CHECK(air_quality BETWEEN 0 AND 500)`
- `CHECK(humidity BETWEEN 0.00 AND 100.00)`
- `CHECK(temperature BETWEEN -50.00 AND 80.00)`
- `CHECK(pressure BETWEEN 300.00 AND 1200.00)`

Indexes:
- `idx_sensor_data_device_uid`
- `idx_sensor_data_recorded_at`
- `idx_sensor_data_device_recorded (device_uid, recorded_at DESC)` for latest-reading and history queries

## Table: `alerts` (future, alert-service)

- `id BIGSERIAL PRIMARY KEY`
- `device_uid VARCHAR(100) NOT NULL`
- `sensor_type VARCHAR(30) NOT NULL`
- `sensor_value DECIMAL(10,2) NOT NULL`
- `severity VARCHAR(20) NOT NULL`
- `message TEXT`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`

Constraints:
- `CHECK(sensor_type IN ('TEMPERATURE','HUMIDITY','AIR_QUALITY','PRESSURE','RAIN'))`
- `CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))`

## Table: `users` (future, auth-service)

- `id BIGSERIAL PRIMARY KEY`
- `name VARCHAR(120) NOT NULL`
- `email VARCHAR(180) UNIQUE NOT NULL`
- `password VARCHAR(255) NOT NULL` (store hashed values only, e.g., BCrypt)
- `role VARCHAR(20) NOT NULL DEFAULT 'USER'`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`

Constraints:
- `UNIQUE(email)`
- `CHECK(role IN ('ADMIN','USER','VIEWER'))`

## Foreign Keys

In strict microservices architecture, there are no cross-service DB foreign keys.  
`device_uid` is intentionally used as an application-level reference key between service-owned databases.

## Phase 1 schema execution note

`schema.sql` is a canonical reference script. In database-per-service deployments, apply only service-owned tables in each service database/schema.

## Why this design

- Supports independent service scaling and deployments.
- Preserves strong integrity within each service boundary.
- Optimizes high-frequency sensor ingestion and per-device time-series lookups.

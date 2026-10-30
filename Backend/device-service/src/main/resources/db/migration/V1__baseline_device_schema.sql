-- Baseline schema for device_db.
--
-- Flyway owns this schema in every environment; Hibernate runs in validate-only
-- mode so an entity that drifts from these tables fails at startup rather than
-- silently altering production data.

-- ---------------------------------------------------------------------------
-- devices — the station registry and its AWS IoT identity
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS devices (
    id                    BIGSERIAL PRIMARY KEY,
    device_uid            VARCHAR(100)  NOT NULL UNIQUE,
    device_name           VARCHAR(150)  NOT NULL,
    location_name         VARCHAR(200)  NOT NULL,
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,
    status                VARCHAR(20)   NOT NULL,
    battery_level         INTEGER,
    wifi_signal_strength  INTEGER,
    firmware_version      VARCHAR(50),
    -- AWS IoT Core identity. The private key never leaves the device and is
    -- never stored here; only the public certificate identifiers are kept.
    thing_name            VARCHAR(150),
    certificate_id        VARCHAR(100),
    certificate_arn       VARCHAR(255),
    iot_connection_status VARCHAR(50),
    public_visible        BOOLEAN       NOT NULL DEFAULT TRUE,
    last_seen_at          TIMESTAMP,
    registered_at         TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP
);

-- The offline sweep scans (status, last_seen_at) across the whole fleet each minute.
CREATE INDEX IF NOT EXISTS idx_devices_status_last_seen ON devices (status, last_seen_at);
-- Public portal filters on visibility before reading coordinates.
CREATE INDEX IF NOT EXISTS idx_devices_public_visible   ON devices (public_visible);
CREATE INDEX IF NOT EXISTS idx_devices_coordinates      ON devices (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_devices_thing_name       ON devices (thing_name);

-- ---------------------------------------------------------------------------
-- device_digital_twins — latest known state per station (AWS IoT shadow shape)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_digital_twins (
    id                   BIGSERIAL PRIMARY KEY,
    device_uid           VARCHAR(100) NOT NULL UNIQUE,
    temperature          NUMERIC(5, 2),
    temperature_status   VARCHAR(20),
    humidity             NUMERIC(5, 2),
    humidity_status      VARCHAR(20),
    air_quality          INTEGER,
    air_quality_status   VARCHAR(20),
    pressure             NUMERIC(7, 2),
    pressure_status      VARCHAR(20),
    rain                 BOOLEAN,
    rain_status          VARCHAR(20),
    oxygen               NUMERIC(5, 2),
    oxygen_status        VARCHAR(20),
    connection_status    VARCHAR(20),
    battery_level        INTEGER,
    wifi_signal_strength INTEGER,
    firmware_version     VARCHAR(50),
    last_latitude        DOUBLE PRECISION,
    last_longitude       DOUBLE PRECISION,
    last_seen_at         TIMESTAMP,
    last_updated_at      TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- firmware — OTA catalogue. Binaries live in S3; only metadata is stored here.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS firmware (
    id            BIGSERIAL PRIMARY KEY,
    version       VARCHAR(50)  NOT NULL UNIQUE,
    file_url      VARCHAR(500) NOT NULL,
    checksum      VARCHAR(64)  NOT NULL,
    file_size     BIGINT       NOT NULL,
    release_notes VARCHAR(1000),
    s3_bucket     VARCHAR(100),
    s3_key        VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL
);

-- ---------------------------------------------------------------------------
-- device_firmware_updates — per-device OTA rollout history
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_firmware_updates (
    id               BIGSERIAL PRIMARY KEY,
    device_uid       VARCHAR(100) NOT NULL,
    firmware_version VARCHAR(50)  NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    started_at       TIMESTAMP    NOT NULL,
    completed_at     TIMESTAMP,
    error_message    VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_firmware_updates_device ON device_firmware_updates (device_uid, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_firmware_updates_status ON device_firmware_updates (status);

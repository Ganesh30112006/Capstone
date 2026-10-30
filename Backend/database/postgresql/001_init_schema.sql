CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(180) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'USER', 'VIEWER'))
);

CREATE TABLE IF NOT EXISTS devices (
    id BIGSERIAL PRIMARY KEY,
    device_uid VARCHAR(100) NOT NULL,
    device_name VARCHAR(150) NOT NULL,
    location VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_devices_device_uid UNIQUE (device_uid),
    CONSTRAINT chk_devices_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE', 'OFFLINE'))
);

CREATE TABLE IF NOT EXISTS sensor_data (
    id BIGSERIAL PRIMARY KEY,
    device_uid VARCHAR(100) NOT NULL,
    temperature NUMERIC(5,2) NOT NULL,
    humidity NUMERIC(5,2) NOT NULL,
    air_quality INTEGER NOT NULL,
    pressure NUMERIC(7,2) NOT NULL,
    rain BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sensor_temperature_range CHECK (temperature BETWEEN -50 AND 80),
    CONSTRAINT chk_sensor_humidity_range CHECK (humidity BETWEEN 0 AND 100),
    CONSTRAINT chk_sensor_air_quality CHECK (air_quality BETWEEN 0 AND 500),
    CONSTRAINT chk_sensor_pressure_range CHECK (pressure BETWEEN 300 AND 1200)
);

CREATE TABLE IF NOT EXISTS alerts (
    id BIGSERIAL PRIMARY KEY,
    device_uid VARCHAR(100) NOT NULL,
    sensor_type VARCHAR(30) NOT NULL,
    sensor_value NUMERIC(10,2) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_alerts_sensor_type CHECK (sensor_type IN ('TEMPERATURE', 'HUMIDITY', 'AIR_QUALITY', 'PRESSURE', 'RAIN')),
    CONSTRAINT chk_alerts_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_sensor_data_device_uid_recorded_at
    ON sensor_data (device_uid, recorded_at DESC);

CREATE INDEX IF NOT EXISTS idx_sensor_data_recorded_at
    ON sensor_data (recorded_at DESC);

CREATE INDEX IF NOT EXISTS idx_alerts_device_uid_created_at
    ON alerts (device_uid, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_devices_status
    ON devices (status);

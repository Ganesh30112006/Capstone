CREATE TABLE IF NOT EXISTS devices (
    id BIGSERIAL PRIMARY KEY,
    device_uid VARCHAR(100) NOT NULL UNIQUE,
    device_name VARCHAR(150) NOT NULL,
    location VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_devices_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE', 'OFFLINE'))
);

CREATE INDEX IF NOT EXISTS idx_devices_status ON devices (status);

CREATE TABLE IF NOT EXISTS sensor_data (
    id BIGSERIAL PRIMARY KEY,
    device_uid VARCHAR(100) NOT NULL,
    temperature DECIMAL(5,2) NOT NULL,
    humidity DECIMAL(5,2) NOT NULL,
    air_quality INTEGER NOT NULL,
    pressure DECIMAL(7,2) NOT NULL,
    rain BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sensor_temperature CHECK (temperature BETWEEN -50.00 AND 80.00),
    CONSTRAINT chk_sensor_air_quality CHECK (air_quality BETWEEN 0 AND 500),
    CONSTRAINT chk_sensor_humidity CHECK (humidity BETWEEN 0.00 AND 100.00),
    CONSTRAINT chk_sensor_pressure CHECK (pressure BETWEEN 300.00 AND 1200.00)
);

CREATE INDEX IF NOT EXISTS idx_sensor_data_device_uid ON sensor_data (device_uid);
CREATE INDEX IF NOT EXISTS idx_sensor_data_recorded_at ON sensor_data (recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_sensor_data_device_recorded ON sensor_data (device_uid, recorded_at DESC);

CREATE TABLE IF NOT EXISTS alerts (
    id BIGSERIAL PRIMARY KEY,
    device_uid VARCHAR(100) NOT NULL,
    sensor_type VARCHAR(30) NOT NULL,
    sensor_value DECIMAL(10,2) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_alerts_sensor_type CHECK (sensor_type IN ('TEMPERATURE', 'HUMIDITY', 'AIR_QUALITY', 'PRESSURE', 'RAIN')),
    CONSTRAINT chk_alerts_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(180) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'USER', 'VIEWER'))
);

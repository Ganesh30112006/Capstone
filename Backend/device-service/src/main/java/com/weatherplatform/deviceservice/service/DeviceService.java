package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceHealthResponse;
import com.weatherplatform.deviceservice.dto.DeviceHeartbeatRequest;
import com.weatherplatform.deviceservice.dto.DeviceLocationResponse;
import com.weatherplatform.deviceservice.dto.DeviceLocationUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceRegistrationRequest;
import com.weatherplatform.deviceservice.dto.DeviceResponse;
import com.weatherplatform.deviceservice.dto.DeviceStatusUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceUpdateRequest;
import com.weatherplatform.deviceservice.entity.DeviceStatus;

import java.util.List;

public interface DeviceService {

    DeviceResponse registerDevice(DeviceRegistrationRequest request);

    List<DeviceResponse> getAllDevices();

    DeviceResponse getDeviceByUid(String deviceUid);

    DeviceResponse updateDevice(String deviceUid, DeviceUpdateRequest request);

    void deleteDevice(String deviceUid);

    DeviceResponse updateStatus(String deviceUid, DeviceStatusUpdateRequest request);

    DeviceHealthResponse getDeviceHealth(String deviceUid);

    List<DeviceResponse> getDevicesByStatus(DeviceStatus status);

    void recordHeartbeat(String deviceUid);

    /**
     * Applies a heartbeat and announces it on the bus.
     *
     * For heartbeats that arrive over HTTP, where the rest of the platform has
     * not seen them yet.
     */
    DeviceHealthResponse processHeartbeat(String deviceUid, DeviceHeartbeatRequest request);

    /**
     * Applies a heartbeat without announcing it.
     *
     * For heartbeats that arrived <em>from</em> the bus. Publishing those
     * re-emits the event that triggered this call, and the listener consumes
     * its own output: one heartbeat from a station became an unbounded stream
     * of heartbeats, which is what happened in the first live run -- roughly
     * sixty real heartbeats had amplified into sixty thousand messages and were
     * still multiplying, drowning telemetry out of the live feed.
     */
    DeviceHealthResponse applyHeartbeat(String deviceUid, DeviceHeartbeatRequest request);

    DeviceLocationResponse getLocation(String deviceUid);

    DeviceLocationResponse updateLocation(String deviceUid, DeviceLocationUpdateRequest request);
}

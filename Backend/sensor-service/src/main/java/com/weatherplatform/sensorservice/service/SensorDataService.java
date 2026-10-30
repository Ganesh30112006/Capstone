package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.dto.PagedSensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.dto.SensorStatisticsResponse;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

public interface SensorDataService {

    SensorDataResponse storeSensorData(SensorDataRequest request);

    SensorDataResponse getLatestByDeviceUid(String deviceUid);

    /**
     * Latest reading for each requested device, keyed by device UID.
     *
     * A device with no reading is simply absent from the map rather than
     * present with a null or a zeroed row: the caller has to be able to tell
     * "this station has not reported" from "this station reported zero".
     */
    Map<String, SensorDataResponse> getLatestForEach(Collection<String> deviceUids);

    PagedSensorDataResponse getHistoryByDeviceUid(String deviceUid, int page, int size);

    PagedSensorDataResponse getHistoryByDeviceUidBetweenDates(
            String deviceUid, LocalDateTime startDate, LocalDateTime endDate, int page, int size);

    SensorStatisticsResponse getStatisticsByDeviceUid(String deviceUid);

    SensorHealthResponse getSensorHealth(String deviceUid);

    /**
     * Diagnostics for several devices in one call, keyed by device UID.
     *
     * A device with no diagnostics on file is absent from the map rather than
     * present with an empty, healthy-looking report.
     */
    Map<String, SensorHealthResponse> getSensorHealthForEach(Collection<String> deviceUids);
}

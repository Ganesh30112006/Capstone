package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceFirmwareUpdateResponse;
import com.weatherplatform.deviceservice.dto.FirmwareCreateRequest;
import com.weatherplatform.deviceservice.dto.FirmwareResponse;
import com.weatherplatform.deviceservice.dto.FirmwareUpdateRequest;
import com.weatherplatform.deviceservice.dto.FirmwareUpdateStatusRequest;

import java.util.List;

public interface FirmwareService {

    FirmwareResponse createFirmware(FirmwareCreateRequest request);

    List<FirmwareResponse> getAllFirmware();

    FirmwareResponse getFirmwareByVersion(String version);

    DeviceFirmwareUpdateResponse requestUpdate(String deviceUid, FirmwareUpdateRequest request);

    DeviceFirmwareUpdateResponse updateStatus(String deviceUid, Long updateId, FirmwareUpdateStatusRequest request);

    List<DeviceFirmwareUpdateResponse> getFirmwareHistory(String deviceUid);
}

package com.weatherplatform.alertservice.service;

import com.weatherplatform.alertservice.dto.AlertResponse;
import com.weatherplatform.alertservice.dto.AlertRuleRequest;
import com.weatherplatform.alertservice.dto.AlertRuleResponse;
import com.weatherplatform.alertservice.dto.AlertStatusUpdateRequest;
import com.weatherplatform.alertservice.event.AnomalyDetectedEvent;
import com.weatherplatform.alertservice.event.DeviceOfflineEvent;
import com.weatherplatform.alertservice.event.SensorFailureDetectedEvent;
import com.weatherplatform.alertservice.event.SensorReadingEvent;

import java.util.List;

public interface AlertService {

    void processSensorReading(SensorReadingEvent event);

    void processSensorFailure(SensorFailureDetectedEvent event);

    void processAnomaly(AnomalyDetectedEvent event);

    void processDeviceOffline(DeviceOfflineEvent event);

    AlertRuleResponse createRule(AlertRuleRequest request);

    List<AlertRuleResponse> getRulesForDevice(String deviceUid);

    void deleteRule(Long ruleId);

    List<AlertResponse> getAlertsForDevice(String deviceUid, String status);

    List<AlertResponse> getAllAlerts(String status);

    List<com.weatherplatform.alertservice.dto.AnomalyResponse> getAnomalies();

    AlertResponse updateAlertStatus(Long alertId, AlertStatusUpdateRequest request);
}

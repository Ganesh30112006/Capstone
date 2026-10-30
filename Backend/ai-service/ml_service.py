"""Orchestrates the three detectors behind one analysis call.

Two entry points, deliberately different in cost:

  * :meth:`analyze_anomaly` forecasts as part of scoring. It is what the HTTP
    API calls, where each request is self-contained.
  * :meth:`analyze_with_forecast` takes a forecast that has already been
    computed. It is what the live-stream worker calls, so a single batched
    Chronos pass can score many subsequent readings instead of one.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Dict, List, Optional, Sequence

import config
from anomaly_scorer import anomaly_scorer
from chronos_forecaster import ForecastResult, chronos_forecaster
from flatline_detector import flatline_detector
from isolation_forest_model import isolation_forest
from model_manager import model_manager
from models import (
    AnomalyAnalysisRequest,
    AnomalyAnalysisResponse,
    AnomalyLevel,
    ForecastRequest,
    ForecastResponse,
    ModelInfo,
    PredictionRequest,
)
from rule_engine import rule_engine


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class MLOrchestrator:

    # ------------------ Hybrid analysis ------------------

    def analyze_anomaly(self, request: AnomalyAnalysisRequest) -> AnomalyAnalysisResponse:
        """Full analysis, including a fresh one-step forecast."""
        current = request.current.model_dump()
        history = [reading.model_dump() for reading in request.history] if request.history else []
        forecast_res = chronos_forecaster.forecast_and_evaluate(history, current)
        return self._build_response(request.deviceUid, current, history, forecast_res)

    def analyze_with_forecast(
        self,
        device_uid: str,
        current: Dict[str, float],
        history: Sequence[dict],
        expected_series: Optional[Dict[str, List[float]]],
    ) -> AnomalyAnalysisResponse:
        """Analysis that reuses a forecast computed earlier.

        The residual comparison is arithmetic; the forecast behind it is a
        transformer forward pass. Only the first is worth repeating per reading,
        so the stream worker holds a multi-step forecast and scores against the
        step that corresponds to each new reading.
        """
        if expected_series:
            forecast_res = chronos_forecaster.evaluate_against(expected_series, history, current)
        else:
            forecast_res = ForecastResult({}, {}, {}, ["No current forecast for this device."],
                                          available=False)
        return self._build_response(device_uid, current, history, forecast_res)

    def _build_response(self, device_uid: str, current: Dict[str, float],
                        history: Sequence[dict], forecast_res) -> AnomalyAnalysisResponse:
        rule_res = rule_engine.evaluate(current)
        if_res = isolation_forest.evaluate(current)
        # Keyed by device and stateful, not windowed: separating a frozen
        # channel from a quantised-stable one needs a longer run of evidence
        # than any window the forecaster retains.
        flat_res = flatline_detector.observe(device_uid, current)
        analysis = anomaly_scorer.combine(rule_res, if_res, forecast_res, current, flat_res)

        return AnomalyAnalysisResponse(
            deviceUid=device_uid,
            timestamp=_utc_now(),
            forecast=forecast_res.expected,
            anomaly=AnomalyLevel(
                detected=analysis.is_anomaly,
                score=analysis.score,
                severity=analysis.severity,
                sensor=analysis.max_sensor,
            ),
            model=ModelInfo(
                forecastModel=config.CHRONOS_MODEL,
                anomalyModel="IsolationForest (scikit-learn)",
                version="1.0",
            ),
            reasons=analysis.reasons,
        )

    # ------------------ Forecasting ------------------

    def forecast(self, request: ForecastRequest) -> ForecastResponse:
        history = [reading.model_dump() for reading in request.history] if request.history else []
        current = history[-1] if history else {}
        forecast_res = chronos_forecaster.forecast_and_evaluate(history, current)
        return ForecastResponse(
            deviceUid=request.deviceUid,
            expected_values=forecast_res.expected,
            reasons=forecast_res.reasons,
        )

    # ------------------ Legacy compatibility ------------------

    def predict_legacy(self, request: PredictionRequest) -> dict:
        if request.readings:
            current = request.readings[-1].model_dump()
            history = [reading.model_dump() for reading in request.readings[:-1]]
        else:
            current = {
                "temperature": request.temperature or 25.0,
                "humidity": request.humidity or 50.0,
                "airQuality": request.airQuality or 50.0,
                "oxygen": request.oxygen or 20.9,
                "pressure": request.pressure or 1013.25,
            }
            history = []

        analysis = self.analyze_anomaly(AnomalyAnalysisRequest(
            deviceUid=request.deviceUid,
            current=current,
            history=history,
        ))

        sensor = analysis.anomaly.sensor
        value = current.get(sensor) if sensor else None

        if analysis.anomaly.detected:
            message = f"Analysis Score: {analysis.anomaly.score}. Reasons: {'; '.join(analysis.reasons)}"
        else:
            message = f"Parameters normal. Score: {analysis.anomaly.score}"

        return {
            "deviceUid": request.deviceUid,
            "anomaly": analysis.anomaly.detected,
            "sensor": sensor,
            "value": value,
            # Legacy field name. This carries the anomaly score, which is a
            # distance from expectation and not a calibrated confidence.
            "confidence": analysis.anomaly.score,
            "message": message,
        }

    # ------------------ Model management ------------------

    def train_model(self, df):
        return isolation_forest.train(df, is_synthetic=False)

    def get_model_info(self):
        metadata = model_manager.load_metadata(config.METADATA_PATH)
        return {
            "forecast_model": config.CHRONOS_MODEL,
            "forecast_available": chronos_forecaster.is_available,
            "anomaly_model": metadata.get("model_name", "IsolationForest"),
            "anomaly_version": metadata.get("version", "unknown"),
            "anomaly_available": isolation_forest.is_available,
            "anomaly_degraded": isolation_forest.degraded_reason,
            "trained_at": metadata.get("training_timestamp", "unknown"),
            "hyperparameters": metadata.get("hyperparameters", {}),
        }


orchestrator = MLOrchestrator()

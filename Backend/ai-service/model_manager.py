"""Model metadata: what was trained, from what, and when.

Written the same way the artifact is — staged beside the target and moved into
place. The two are read together at startup to decide whether the detector is
trustworthy, so a metadata file truncated by a crash mid-write would make a
perfectly good model look unidentifiable.
"""

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict


class ModelManager:
    @staticmethod
    def save_metadata(path: Path, metadata: Dict[str, Any]) -> None:
        os.makedirs(path.parent, exist_ok=True)
        staging = path.with_suffix(path.suffix + ".tmp")
        with open(staging, "w", encoding="utf-8") as handle:
            json.dump(metadata, handle, indent=4)
            handle.flush()
            # The rename below is atomic, but only orders against data that has
            # reached the filesystem. Without this the metadata can survive a
            # crash as a correctly named, empty file.
            os.fsync(handle.fileno())
        staging.replace(path)

    @staticmethod
    def load_metadata(path: Path) -> Dict[str, Any]:
        if not path.exists():
            return {}
        try:
            with open(path, "r", encoding="utf-8") as handle:
                loaded = json.load(handle)
        except (json.JSONDecodeError, OSError):
            # Unreadable metadata must not stop the service starting: it is
            # descriptive, and the model beside it is what actually scores.
            return {}
        return loaded if isinstance(loaded, dict) else {}

    @staticmethod
    def create_metadata(model_name: str, version: str, features: list, hyperparams: dict,
                        metrics: dict = None) -> Dict[str, Any]:
        return {
            "model_name": model_name,
            "version": version,
            "training_timestamp": datetime.now(timezone.utc).isoformat(),
            "features": features,
            "hyperparameters": hyperparams,
            "metrics": metrics or {},
        }


model_manager = ModelManager()

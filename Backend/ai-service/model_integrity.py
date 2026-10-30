"""Integrity verification for the persisted model artifact.

`joblib.load` is `pickle.load` with a fast array path. Unpickling is not
parsing: a pickle stream can name arbitrary callables and have them invoked
during load. So the model file is not data the service reads — it is code the
service runs, as the service's own user, before any request is served.

That matters here because the artifact lives on a mounted volume that outlives
the container and is written by a network-reachable endpoint
(`POST /api/ai/train`). Anyone who can write that path gets code execution in
the AI container; no exploit is required, only a file.

The mitigation is a keyed HMAC-SHA256 sidecar written at train time and
verified before load. The key never touches the volume, so an attacker who can
write the artifact still cannot produce a signature for it, and the load is
refused. Refusing costs a retrain; loading costs the container.

Unkeyed operation is supported, because a local sandbox has no secret to
inject, but it is reported as degraded rather than treated as fine — and
`AI_MODEL_INTEGRITY_REQUIRED=true` turns it into a hard failure for
environments that must not run unverified.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
from pathlib import Path
from typing import Optional

import config

logger = logging.getLogger(__name__)

# Read in blocks so signing never holds the whole artifact in memory alongside
# the copy joblib is about to build.
_CHUNK = 1024 * 1024


class IntegrityError(Exception):
    """The artifact did not match its signature, or a required one was absent."""


def is_enabled() -> bool:
    return bool(config.MODEL_INTEGRITY_KEY)


def _digest(path: Path) -> str:
    mac = hmac.new(config.MODEL_INTEGRITY_KEY.encode("utf-8"), digestmod=hashlib.sha256)
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(_CHUNK), b""):
            mac.update(block)
    return mac.hexdigest()


def sign(path: Path, signature_path: Optional[Path] = None) -> Optional[str]:
    """Writes the sidecar for a freshly trained artifact. No-op when unkeyed."""
    if not is_enabled():
        return None
    signature_path = signature_path or config.IF_SIGNATURE_PATH
    digest = _digest(path)
    signature_path.parent.mkdir(parents=True, exist_ok=True)
    # Staged like the artifact itself: a half-written signature would fail
    # verification and take the detector down on the next restart.
    staging = signature_path.with_suffix(signature_path.suffix + ".tmp")
    staging.write_text(digest, encoding="utf-8")
    staging.replace(signature_path)
    return digest


def verify(path: Path, signature_path: Optional[Path] = None) -> Optional[str]:
    """Checks the artifact against its sidecar *before* it is unpickled.

    Returns None when the artifact is trustworthy, or a short reason describing
    why it is only tolerated. Raises :class:`IntegrityError` when the artifact
    must not be loaded at all.
    """
    signature_path = signature_path or config.IF_SIGNATURE_PATH

    if not is_enabled():
        reason = "artifact integrity unverified (no AI_MODEL_INTEGRITY_KEY set)"
        if config.MODEL_INTEGRITY_REQUIRED:
            raise IntegrityError(
                "AI_MODEL_INTEGRITY_REQUIRED is set but AI_MODEL_INTEGRITY_KEY is not; "
                "refusing to load an unverifiable model artifact."
            )
        logger.warning(
            "%s. Any process that can write %s can execute code in this container, "
            "because loading a joblib artifact unpickles it.", reason, path,
        )
        return reason

    if not signature_path.exists():
        # Keyed, but unsigned: either a pre-signing artifact or one dropped in
        # by something that did not have the key. Both are refused, because the
        # only way to tell them apart is the signature that is missing.
        raise IntegrityError(
            f"No integrity signature at {signature_path} for a keyed deployment. "
            "Retrain via POST /api/ai/train to produce a signed artifact."
        )

    expected = signature_path.read_text(encoding="utf-8").strip()
    actual = _digest(path)
    # Constant-time: a byte-by-byte comparison leaks how much of a forged
    # digest was correct, which is enough to build the rest of it.
    if not hmac.compare_digest(expected, actual):
        raise IntegrityError(
            f"Model artifact at {path} does not match its signature. "
            "It has been modified since it was trained; refusing to load it."
        )

    logger.info("Model artifact integrity verified against %s", signature_path.name)
    return None

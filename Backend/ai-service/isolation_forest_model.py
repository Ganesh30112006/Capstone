"""Multivariate outlier detection over a single reading.

The scaler and the forest are persisted together as one scikit-learn Pipeline.
They must be: an IsolationForest trained on standardised features is meaningless
when fed raw ones, and a StandardScaler that is saved separately (or not at all)
silently turns the whole detector off. That is not hypothetical — the previous
version dumped only the forest, so every restarted container reloaded the model,
raised NotFittedError inside evaluate(), swallowed it into a reasons string, and
scored every reading 0.0. A reading of 500 degrees was reported as normal.

Two rules follow from that, and both are load-bearing:

  * the artifact is versioned, so a stale layout is detected and refused rather
    than half-loaded; and
  * an unusable model reports itself unavailable instead of returning a
    confident-looking 0.0, so the hybrid scorer can discount it and /health can
    show it.
"""

from __future__ import annotations

import logging
import threading
from typing import List, Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import config
import metrics
import model_integrity
from model_manager import model_manager

logger = logging.getLogger(__name__)

FEATURES: List[str] = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]

# Bumped when the artifact layout changes.
#   v1 stored a bare IsolationForest with no scaler; reloading it produced a
#     detector that scored everything 0.0.
#   v2 bundled the scaler, but mapped decision_function onto [0, 1] with a fixed
#     offset that put the decision boundary at 0.5 — so a plainly normal reading
#     scored ~0.39 and a clear outlier ~0.69, and nothing ever approached either
#     end of the scale.
#   v3 carries a calibration constant measured on the training data, so the
#     score means "how far into the anomalous tail of what this model was
#     trained on", with normal readings near 0.
# Older layouts are deliberately unreadable rather than silently misinterpreted.
ARTIFACT_VERSION = "3.0"

# Used when an artifact carries no calibration, which should not happen now that
# the version is refused, but a scoring path must never divide by None.
DEFAULT_ANOMALY_SCALE = 0.05

# Rows sampled from the probe when verifying the fast scoring path. See
# _FastScorer._matches for why this is capped rather than exhaustive.
_VERIFY_ROWS = 128


class UnrepresentableReading(ValueError):
    """A reading the forest cannot score, as opposed to a broken scorer.

    Distinguished from an ordinary failure so that hostile input cannot switch
    the fast path off: without this, one reading of 1e38 would trip the
    "something is wrong with the optimisation" branch and cost the service its
    scoring speed for the lifetime of the process.
    """


class _FastScorer:
    """A bit-exact shortcut around Pipeline.decision_function for one reading.

    Scoring a single reading spends almost none of its time in the trees. The
    forest asks each of its 50 estimators for a leaf index, and `tree.apply`
    re-runs `check_is_fitted` and a feature-count check *per tree, per reading*
    — fifty rounds of Python-level bookkeeping wrapped around fifty Cython
    calls that each take well under a microsecond. Measured, one reading cost
    ~510 us through the pipeline, and the same arithmetic done directly costs
    ~79 us: a 6.5x saving on what profiling showed to be 99% of the service's
    per-reading cost (every other detector in the stack runs in about 2 us).

    Three things make that saving available:

      * the per-tree validation is skipped, because the input has already been
        validated by the caller and the forest is fitted once at load time;
      * the two path-length lookup tables sklearn keeps per tree are summed
        into one at build time, so the hot loop does one array index per tree
        instead of two plus arithmetic; and
      * the single-row case is specialised to Python floats, which avoids
        allocating fifty one-element numpy arrays per reading.

    This reaches into sklearn internals, so it is treated as an optimisation
    that has to prove itself rather than one that is assumed to work. `build`
    verifies the fast path against `Pipeline.decision_function` over the
    training matrix plus deliberately extreme rows, and returns None on any
    mismatch or missing attribute — in which case scoring simply stays on the
    sklearn path. A version bump that changes these internals therefore costs
    speed, never correctness.
    """

    __slots__ = ("_pairs", "_denominator", "_offset", "_mean", "_scale", "_n_features")

    def __init__(self, pairs, denominator, offset, mean, scale, n_features):
        self._pairs = pairs
        self._denominator = denominator
        self._offset = offset
        self._mean = mean
        self._scale = scale
        self._n_features = n_features

    @classmethod
    def build(cls, pipeline, probe: np.ndarray) -> Optional["_FastScorer"]:
        """Returns a verified scorer, or None to stay on the sklearn path."""
        try:
            from sklearn.ensemble._iforest import _average_path_length

            scaler = pipeline.named_steps["scaler"]
            forest = pipeline.named_steps["forest"]

            # Only the plain standardisation is reproduced here. A scaler
            # configured without centring or scaling would need different
            # arithmetic, so it is refused rather than approximated.
            if not (getattr(scaler, "with_mean", True) and getattr(scaler, "with_std", True)):
                return None

            n_features = int(forest.n_features_in_)
            # Feature subsampling would mean a different column subset per
            # tree. The service never configures it; rather than carry an
            # untested branch, the fast path declines.
            if int(getattr(forest, "_max_features", n_features)) != n_features:
                return None

            pairs = []
            for index, estimator in enumerate(forest.estimators_):
                lookup = (np.asarray(forest._decision_path_lengths[index], dtype=np.float64)
                          + np.asarray(forest._average_path_length_per_tree[index], dtype=np.float64)
                          - 1.0)
                pairs.append((estimator.tree_, lookup))

            # _average_path_length returns an array, one entry per input. Taking
            # the element explicitly rather than calling float() on the array:
            # numpy deprecated that conversion and will raise on it.
            denominator = float(
                np.asarray(_average_path_length([forest._max_samples])).reshape(-1)[0]
            ) * len(forest.estimators_)
            if not np.isfinite(denominator) or denominator == 0.0:
                return None

            scorer = cls(
                pairs=pairs,
                denominator=denominator,
                offset=float(forest.offset_),
                mean=np.asarray(scaler.mean_, dtype=np.float64),
                scale=np.asarray(scaler.scale_, dtype=np.float64),
                n_features=n_features,
            )
        except Exception:
            logger.debug("Fast scoring path unavailable; using scikit-learn.", exc_info=True)
            return None

        if not scorer._matches(pipeline, probe):
            logger.warning(
                "Fast Isolation Forest scoring path disagreed with scikit-learn "
                "and has been disabled; scoring continues on the library path.")
            return None
        return scorer

    def _matches(self, pipeline, probe: np.ndarray) -> bool:
        """Exact agreement, not approximate.

        The two paths run the same arithmetic in the same order on the same
        dtypes, so anything other than a bit-identical result means an
        assumption above has stopped holding. Tolerating a small difference
        here would be tolerating a difference nobody has explained.
        """
        try:
            rows = np.asarray(probe, dtype=np.float64)
            if rows.ndim != 2 or rows.shape[0] == 0:
                return False
            # Capped, and evenly spaced rather than the first N, so a sorted
            # training set is still sampled across its whole range.
            #
            # Verification cost is linear in the probe, and the probe was the
            # caller's whole training matrix: verifying a 25,000-row training
            # run meant scoring 75,001 rows one at a time, which took training
            # from 254 ms to 16 s. The check is a guard against scikit-learn
            # internals moving underneath this, and that is a structural
            # failure — it shows up on the first row, not the ten-thousandth.
            # A few hundred rows spanning the distribution buy what a full pass
            # buys, in a fortieth of the time.
            if rows.shape[0] > _VERIFY_ROWS:
                rows = rows[np.linspace(0, rows.shape[0] - 1, _VERIFY_ROWS).astype(int)]
            # The training distribution, plus rows far outside it, so the check
            # covers the extreme leaves an ordinary sample never reaches.
            extremes = np.vstack([rows, rows * 50.0, -rows * 50.0,
                                  np.zeros((1, rows.shape[1]), dtype=np.float64)])
            reference = pipeline.decision_function(extremes)
            for index in range(extremes.shape[0]):
                if self.decision(extremes[index]) != reference[index]:
                    return False

            # Readings past the float32 range are checked separately, because
            # agreeing here means both paths *refusing*, and a batch containing
            # one of these would make scikit-learn refuse the whole batch.
            # Fuzzing found this to be the one input class where the two paths
            # had disagreed, so it is now part of what the fast path must prove.
            width = rows.shape[1]
            for magnitude in (1e38, 1e39, 1e300, -1e39):
                probe_row = np.full((1, width), magnitude, dtype=np.float64)
                if self._agrees_on_refusal(pipeline, probe_row):
                    continue
                return False
            return True
        except Exception:
            logger.debug("Fast path verification failed.", exc_info=True)
            return False

    @staticmethod
    def _refuses(call) -> bool:
        try:
            call()
        except Exception:
            return True
        return False

    def _agrees_on_refusal(self, pipeline, row: np.ndarray) -> bool:
        """Both paths must accept the row, or both must refuse it."""
        with np.errstate(over="ignore", invalid="ignore"):
            library_refused = self._refuses(lambda: pipeline.decision_function(row))
            fast_refused = self._refuses(lambda: self.decision(row))
        return library_refused == fast_refused

    def decision(self, row: np.ndarray) -> float:
        """decision_function for one reading. Negative means anomalous.

        Raises UnrepresentableReading for a reading the forest cannot take,
        which is what scikit-learn does with the same input and therefore what
        this has to do to stay interchangeable with it.
        """
        with np.errstate(over="ignore", invalid="ignore"):
            scaled = np.ascontiguousarray(
                ((np.asarray(row, dtype=np.float64).reshape(1, -1) - self._mean) / self._scale),
                dtype=np.float32,
            )
        # The trees are float32. A reading can be finite as a float64 and still
        # overflow that — anything past ~3.4e38, and smaller values once divided
        # by a narrow feature's scale — at which point the cast yields infinity.
        #
        # scikit-learn rejects the reading at this point, so scoring it anyway
        # would make the service's answer depend on whether this optimisation
        # happened to install. Found by fuzzing: before this check, a reading of
        # 1e38 scored 1.0 on the fast path and came back unscored on the library
        # path.
        if not np.isfinite(scaled).all():
            raise UnrepresentableReading(
                "reading is outside the range the forest can represent")

        total = 0.0
        for tree, lookup in self._pairs:
            total += lookup[tree.apply(scaled)[0]]
        return -(2.0 ** (-total / self._denominator)) - self._offset


class IsolationForestResult:
    __slots__ = ("is_anomaly", "score", "reasons", "available")

    def __init__(self, is_anomaly: bool, score: float, reasons: list, available: bool = True):
        self.is_anomaly = is_anomaly
        self.score = score
        self.reasons = reasons
        # False when the model could not score at all, so the scorer can
        # renormalise instead of treating "no signal" as "nothing wrong".
        self.available = available


class IsolationForestModel:

    def __init__(self):
        self._lock = threading.Lock()
        self._pipeline: Optional[Pipeline] = None
        self.features = FEATURES
        self.degraded_reason: Optional[str] = None
        self._load_or_init()

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    @property
    def model(self):
        """The fitted pipeline, or None. Read without the lock: rebinding is atomic."""
        return self._pipeline

    @property
    def is_available(self) -> bool:
        return self._pipeline is not None

    def _load_or_init(self) -> None:
        path = config.IF_MODEL_PATH
        if not path.exists():
            logger.info("No Isolation Forest artifact at %s; bootstrapping from synthetic data", path)
            self._train_synthetic()
            return

        # Checked *before* joblib.load, not after: loading is unpickling, and
        # unpickling a hostile artifact executes its payload during the load
        # itself. A check afterwards would run the code it was meant to prevent.
        try:
            integrity_note = model_integrity.verify(path)
        except model_integrity.IntegrityError as exc:
            logger.error("%s Retraining from synthetic data instead.", exc)
            self.degraded_reason = "artifact failed integrity verification"
            metrics.SECURITY_REJECTIONS.labels("model_integrity").inc()
            self._train_synthetic()
            return

        try:
            loaded = joblib.load(path)
        except Exception:
            logger.exception("Could not read the Isolation Forest artifact at %s", path)
            self.degraded_reason = "artifact unreadable"
            self._train_synthetic()
            return

        if not isinstance(loaded, Pipeline) or "scaler" not in loaded.named_steps:
            # A pre-2.0 artifact: the forest without its scaler. Loading it would
            # score standardised-space decisions against raw inputs, so it is
            # refused rather than trusted.
            logger.error(
                "Isolation Forest artifact at %s predates the bundled scaler (v%s expected). "
                "Refusing to load it; retraining instead. Retrain on real data via POST /api/ai/train.",
                path, ARTIFACT_VERSION,
            )
            self.degraded_reason = "artifact predates the bundled scaler"
            self._train_synthetic()
            return

        if getattr(loaded, "anomaly_scale_", None) is None:
            # A v2 artifact. It would load and score, but against the old fixed
            # mapping, whose numbers mean something different from the threshold
            # they are now compared with. Scoring it would be worse than not.
            logger.error(
                "Isolation Forest artifact at %s predates score calibration (v%s expected). "
                "Refusing to load it; retraining instead.", path, ARTIFACT_VERSION,
            )
            self.degraded_reason = "artifact predates score calibration"
            self._train_synthetic()
            return

        self._attach_fast_scorer(loaded, self._probe_from_scaler(loaded))
        self._pipeline = loaded
        metrics.MODEL_AVAILABLE.labels("isolation_forest").set(1)
        # An operator needs to know when the detector is running on an invented
        # distribution rather than the fleet's own readings.
        metadata = model_manager.load_metadata(config.METADATA_PATH)
        if metadata.get("metrics", {}).get("is_synthetic"):
            self.degraded_reason = "trained on synthetic data"
        elif integrity_note:
            self.degraded_reason = integrity_note
        logger.info("Loaded Isolation Forest pipeline (scaler bundled) from %s%s", path,
                    f" — {self.degraded_reason}" if self.degraded_reason else "")

    def _train_synthetic(self) -> None:
        """Bootstrap so the service is never wholly without a detector.

        Marked synthetic in the metadata: this distribution is invented, not
        observed, so it must never be mistaken for a model trained on the fleet.
        """
        rng = np.random.default_rng(42)
        n = 500
        df = pd.DataFrame({
            "temperature": np.clip(rng.normal(25, 5, n), 10, 40),
            "humidity": np.clip(rng.normal(55, 10, n), 20, 85),
            "airQuality": np.clip(rng.normal(50, 20, n), 10, 150),
            "oxygen": np.clip(rng.normal(20.9, 0.3, n), 19.5, 22),
            "pressure": np.clip(rng.normal(1013, 10, n), 980, 1040),
        })
        self.train(df, is_synthetic=True)

    # ------------------------------------------------------------------
    # Training
    # ------------------------------------------------------------------

    @staticmethod
    def _attach_fast_scorer(pipeline, probe: Optional[np.ndarray]) -> None:
        """Builds the verified fast scoring path and hangs it on the pipeline.

        Attached to the pipeline rather than kept as a separate attribute so a
        concurrent reader cannot pair a new pipeline with an old scorer: the
        two travel together through the single rebinding that publishes a
        model, exactly as `anomaly_scale_` does.

        Never attached before the artifact is written. The lookup tables it
        holds are derived from the trees and would otherwise be pickled into
        the artifact alongside the trees they came from.
        """
        if probe is None or len(probe) == 0:
            pipeline._fast_scorer = None
            return
        pipeline._fast_scorer = _FastScorer.build(pipeline, probe)

    def _probe_from_scaler(self, pipeline) -> Optional[np.ndarray]:
        """Representative rows for verifying a model that was loaded, not fitted.

        A loaded artifact arrives without the data it was trained on, but the
        scaler carries that distribution's mean and spread, which is enough to
        generate rows landing in the same region of the trees. Seeded, so a
        model either verifies on every start or on none of them.
        """
        try:
            scaler = pipeline.named_steps["scaler"]
            mean = np.asarray(scaler.mean_, dtype=np.float64)
            scale = np.asarray(scaler.scale_, dtype=np.float64)
        except Exception:
            return None
        rng = np.random.default_rng(0)
        return rng.normal(mean, scale, size=(_VERIFY_ROWS, mean.shape[0]))

    def train(self, data: pd.DataFrame, is_synthetic: bool = False) -> int:
        missing = [column for column in self.features if column not in data.columns]
        if missing:
            raise ValueError(f"Data missing required features: {missing}")

        # A single NaN or infinity in the training set propagates through
        # StandardScaler into every subsequent score — the fitted mean and
        # variance become NaN, and the detector is silently destroyed by one
        # bad row. The HTTP layer rejects non-finite input, but training is
        # also reachable from a DataFrame built elsewhere, so it is refused
        # here too rather than trusted to have been filtered upstream.
        if not np.isfinite(data[self.features].to_numpy(dtype=float)).all():
            raise ValueError(
                "Training data contains NaN or infinite values; every fitted "
                "statistic would become NaN and the detector would stop working."
            )

        minimum = int(getattr(config, "MIN_TRAINING_SAMPLES", 50))
        if len(data) < minimum:
            # Fitting anyway would produce a model that reports SUCCESS and then
            # detects nothing: a scaler fitted on a handful of rows has almost no
            # variance, so every reading standardises to roughly the same point.
            raise ValueError(
                f"At least {minimum} readings are required to train a usable model; got {len(data)}."
            )

        # Fit on a plain array so scoring can pass arrays too, with no
        # feature-name mismatch warnings on every single reading.
        matrix = data[self.features].to_numpy(dtype=float)

        pipeline = Pipeline([
            ("scaler", StandardScaler()),
            ("forest", IsolationForest(n_estimators=config.IF_N_ESTIMATORS,
                                       contamination=config.IF_CONTAMINATION,
                                       random_state=42)),
        ])
        pipeline.fit(matrix)

        # Calibrate the score against the data the model was actually fitted on.
        #
        # decision_function is centred on the contamination boundary: positive
        # inside the normal region, negative outside it, with a magnitude that
        # depends on the feature space and has no fixed range. Mapping it with a
        # constant offset — as v2 did — makes the boundary 0.5, so a plainly
        # normal reading scores ~0.39 and a clear outlier ~0.69. Nothing reaches
        # either end of the scale, and "0.5" silently means "normal".
        #
        # The scale is the spread from a typical training decision down to its
        # 1st percentile, which is how far "normal" reaches in the anomalous
        # direction for this particular fit.
        #
        # Deliberately not a deep-tail order statistic. The first version used
        # the 0.1st percentile — on 4,000 training rows, the 4th most extreme
        # sample, a single point far out in the tail. Measured across six
        # training seeds it varied by 48%, and recall on borderline readings
        # swung between 32% and 89% purely from which samples happened to be
        # drawn. A difference of two well-supported quantiles varies by ~3%
        # instead, so a retrain on equivalent data produces comparable scores.
        train_decisions = pipeline.decision_function(matrix)
        spread = float(np.median(train_decisions) - np.percentile(train_decisions, 1))
        scale = config.IF_CALIBRATION_FACTOR * spread
        # Guarded: perfectly uniform training data can put this at ~0, which
        # would turn every subsequent score into 1.0.
        pipeline.anomaly_scale_ = max(scale, 1e-3)

        config.IF_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
        # Write beside the target and move into place, so a crash mid-write
        # cannot leave a truncated artifact that the next start would refuse.
        staging = config.IF_MODEL_PATH.with_suffix(".joblib.tmp")
        joblib.dump(pipeline, staging)
        staging.replace(config.IF_MODEL_PATH)
        # Signed after the move, so the signature always describes the artifact
        # that is actually in place.
        model_integrity.sign(config.IF_MODEL_PATH)

        model_manager.save_metadata(config.METADATA_PATH, model_manager.create_metadata(
            model_name="IsolationForest_Anomaly",
            version=ARTIFACT_VERSION,
            features=self.features,
            hyperparams={"contamination": config.IF_CONTAMINATION,
                         "n_estimators": config.IF_N_ESTIMATORS,
                         "calibration_factor": config.IF_CALIBRATION_FACTOR},
            metrics={"is_synthetic": is_synthetic, "samples_trained": len(data)},
        ))

        # Built from the real training matrix, which is the strongest probe
        # available: it exercises the leaves this model will actually reach.
        self._attach_fast_scorer(pipeline, matrix)

        # Swap the whole fitted object in one rebinding: a concurrent reader
        # sees either the old pipeline or the new one, never a half-fitted mix.
        with self._lock:
            self._pipeline = pipeline
            self.degraded_reason = None if not is_synthetic else "trained on synthetic data"

        metrics.MODEL_AVAILABLE.labels("isolation_forest").set(1)
        logger.info("Isolation Forest trained on %d samples (synthetic=%s)", len(data), is_synthetic)
        return len(data)

    # ------------------------------------------------------------------
    # Scoring
    # ------------------------------------------------------------------

    def evaluate(self, readings: dict) -> IsolationForestResult:
        pipeline = self._pipeline
        if pipeline is None:
            metrics.MODEL_AVAILABLE.labels("isolation_forest").set(0)
            return IsolationForestResult(False, 0.0, ["Isolation Forest unavailable."], available=False)

        try:
            # A plain 1x5 array: building a DataFrame per reading costs far more
            # than the forest traversal it feeds.
            row = np.asarray([[float(readings.get(name, 0.0)) for name in self.features]], dtype=float)

            if not np.isfinite(row).all():
                # NaN reaching the forest yields a NaN decision, and every
                # comparison against NaN is false — so the reading would be
                # reported as normal. Refusing to score it is the honest answer,
                # and the scorer renormalises around the missing signal.
                metrics.INVALID_READINGS.labels("non_finite").inc()
                return IsolationForestResult(
                    False, 0.0,
                    ["Reading contains a non-numeric value; Isolation Forest could not score it."],
                    available=False,
                )

            # decision_function alone is enough: predict() is just its sign, and
            # asking for both walks every tree in the forest twice.
            #
            # The fast path is read off the pipeline, so it is always the one
            # verified against *this* model; when it is absent — an unusual
            # scaler, a scikit-learn version whose internals moved, a probe that
            # disagreed — scoring falls back to the library call and the only
            # difference is speed.
            fast = getattr(pipeline, "_fast_scorer", None)
            if fast is None:
                decision = float(pipeline.decision_function(row)[0])
            else:
                try:
                    decision = fast.decision(row)
                except UnrepresentableReading:
                    # Not a fault in the fast path: scikit-learn refuses this
                    # reading too. Re-raised so the handler below reports it
                    # unscored, exactly as it would without the optimisation.
                    raise
                except Exception:
                    # Verified at build time, so this should not happen. If it
                    # does, the reading is still scored — losing the detector
                    # for a reading it could have scored would be a worse
                    # outcome than losing the speed-up.
                    logger.exception("Fast scoring path failed; disabling it.")
                    pipeline._fast_scorer = None
                    decision = float(pipeline.decision_function(row)[0])
        except Exception:
            metrics.MODEL_AVAILABLE.labels("isolation_forest").set(0)
            logger.exception("Isolation Forest scoring failed")
            return IsolationForestResult(False, 0.0, ["Isolation Forest could not score this reading."],
                                         available=False)

        # Negative means outside the contamination boundary the model was fitted
        # with; that, not the graded score, is the model's own verdict.
        is_anomaly = decision < 0.0
        # Graded by how far past the boundary, relative to the most anomalous
        # sample in the training set. Normal readings land near 0 rather than in
        # the middle of the range, so the number can be compared with a
        # threshold and read by a person.
        scale = getattr(pipeline, "anomaly_scale_", None) or DEFAULT_ANOMALY_SCALE
        score = max(0.0, min(1.0, -decision / scale))
        reasons = []
        if is_anomaly:
            reasons.append(f"Isolation Forest detected a multivariate outlier (score {score:.2f}).")
        return IsolationForestResult(is_anomaly=is_anomaly, score=score, reasons=reasons, available=True)


isolation_forest = IsolationForestModel()

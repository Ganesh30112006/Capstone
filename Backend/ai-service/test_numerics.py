"""Arithmetic that a faster form must not quietly change.

The residual detector divides by a window's standard deviation, and compares
the result against a fixed sigma threshold. That makes the spread a *divisor*
on a detection path, so an error in it does not stay small — it lands directly
in the number a verdict is read from.

`_population_std` replaces `np.std` for speed. It is held to bit-identical
output rather than a tolerance, because the whole reason it is safe to use is
that it runs numpy's own two-pass algorithm in numpy's own order and dtype. Any
difference at all would mean that is no longer true, and "close enough" is not
something anyone has justified for this value.

The algebraic shortcut var = E[x^2] - E[x]^2 is deliberately not used, and the
last test here is why: this service's features include a pressure channel that
sits near 1013 with a spread under 1, which is precisely the input that makes
that form cancel.
"""

import numpy as np
import pytest

from chronos_forecaster import _population_std

# The real feature distributions. Pressure and oxygen are the interesting ones:
# a large mean with a small spread is what breaks the naive formulation.
DISTRIBUTIONS = {
    "temperature": (20.0, 6.0),
    "humidity": (55.0, 18.0),
    "airQuality": (45.0, 22.0),
    "oxygen": (20.9, 0.5),
    "pressure": (1013.0, 0.6),
}


@pytest.mark.parametrize("feature", list(DISTRIBUTIONS))
def test_bit_identical_to_numpy_on_each_feature(feature):
    mean, spread = DISTRIBUTIONS[feature]
    rng = np.random.default_rng(abs(hash(feature)) % 10_000)
    for _ in range(2000):
        window = rng.normal(mean, spread, 48).astype(np.float32)
        assert _population_std(window) == float(np.std(window, dtype=np.float64))


@pytest.mark.parametrize("size", [1, 2, 3, 8, 16, 32, 48, 64, 128, 2000])
def test_bit_identical_across_window_lengths(size):
    rng = np.random.default_rng(size)
    window = rng.normal(1013.0, 0.6, size).astype(np.float32)
    assert _population_std(window) == float(np.std(window, dtype=np.float64))


@pytest.mark.parametrize("spread", [1e-3, 1e-4, 1e-5, 1e-7, 0.0])
def test_bit_identical_when_a_settled_sensor_barely_moves(spread):
    """The case the naive formula gets wrong, at the offset that makes it wrong.

    A pressure channel on a still day is a large constant plus almost nothing.
    Measured, E[x^2] - E[x]^2 lost 3e-5 relative here — on a value that divides
    the residual.
    """
    rng = np.random.default_rng(5)
    noise = rng.normal(0, spread, 48) if spread else np.zeros(48)
    window = (1013.0 + noise).astype(np.float32)
    assert _population_std(window) == float(np.std(window, dtype=np.float64))


def test_a_frozen_channel_has_zero_spread_and_does_not_raise():
    """The divide-by-spread guard upstream depends on getting a real 0.0 back."""
    frozen = np.full(48, 20.9, dtype=np.float32)
    assert _population_std(frozen) == 0.0


def test_accepts_a_plain_list_and_a_float64_array():
    values = [20.0, 20.5, 21.0, 19.5]
    assert _population_std(values) == float(np.std(np.asarray(values), dtype=np.float64))
    as64 = np.asarray(values, dtype=np.float64)
    assert _population_std(as64) == float(np.std(as64, dtype=np.float64))


def test_the_naive_formulation_really_is_worse():
    """Guards the comment, not the code.

    If this ever stops failing, the reasoning in `_population_std` for avoiding
    the shortcut has stopped applying and should be revisited rather than left
    as folklore.
    """
    rng = np.random.default_rng(11)
    worst = 0.0
    for _ in range(2000):
        window = rng.normal(1013.0, 0.6, 48).astype(np.float32)
        exact = float(np.std(window, dtype=np.float64))
        x = window.astype(np.float64)
        naive = float(np.sqrt(max(np.dot(x, x) / x.size - (x.sum() / x.size) ** 2, 0.0)))
        if exact > 0:
            worst = max(worst, abs(naive - exact) / exact)
    assert worst > 1e-12, (
        "the algebraic shortcut is no longer losing precision on pressure-shaped "
        "windows; the justification in _population_std needs rechecking")

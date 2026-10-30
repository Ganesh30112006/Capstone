# AI service benchmarks

Seven harnesses. The first three answer *does it work*, the next three *what
does it cost*, and the last one is the only one that takes real data. All the
synthetic ones are seeded, so a number that moves between runs is a real change
rather than sampling noise.

None of them touch the deployed model: each redirects `config.IF_MODEL_PATH`
into a temporary directory before importing the model module. That is not
incidental — an earlier version of the test suite retrained over the live
artifact and left a model fitted on a single reading on disk, reporting itself
as production-trained.

```
python -m benchmarks.detection_quality    # does the detector detect?
python -m benchmarks.chronos_validation   # is the real residual detector as good?
python -m benchmarks.flatline_validation  # calm night, or dead sensor?
python -m benchmarks.model_sizing         # how many trees do we need?
python -m benchmarks.performance          # latency and throughput per layer
python -m benchmarks.capacity             # how much fleet fits, and what stops it

python -m benchmarks.field_evaluation telemetry.csv --faults faults.csv
```

## What these do and do not establish

Everything below except `field_evaluation` runs on seeded synthetic streams.
That answers one question, and it is regularly mistaken for a second one it
does not answer at all:

| question | what it asks | status |
|---|---|---|
| **stability** | does it behave the same across seeds and reruns? | measured, ±3.4% |
| **validity** | does our reference data resemble a real station? | **needs field data** |

The quantisation steps, reversion rates and calm periods in `workloads` are
modelled, not observed, and the detector's thresholds were chosen against them.
So the detector is *calibrated*, not *validated*. `field_evaluation` is what
turns the second row into a claim; until it has been run on real telemetry, the
numbers here describe a model of a fleet rather than a fleet.

## detection_quality

The one that matters. Labelled synthetic data with five injected failure modes,
reporting separation, per-kind recall, a threshold sweep, a joint weight sweep,
and what each degraded mode costs.

It exists because the service shipped a detector that could not detect. Rules
were a weighted term in the same sum as the two models and carried the largest
weight, so a reading that broke no physical bound contributed the rule weight to
the denominator and nothing to the numerator. Every score was capped at

    (W_IF + W_RESIDUAL) / (W_RULE + W_IF + W_RESIDUAL) = 0.5

against a threshold of 0.75. Both models pinned at maximum scored 0.500 and did
not fire — the Isolation Forest and the Chronos residual could not report an
anomaly at any input, and the full test suite passed.

**Caveat on the residual detector.** Chronos may not be installed, so this
harness substitutes a persistence forecast — each feature predicted as the mean
of the clean history. That is the weakest thing a real forecaster could do, so
its numbers are a floor rather than a claim about Chronos. Re-run the weight
sweep on a machine with Chronos installed before trusting the tuned weights as
an optimum.

## chronos_validation

Checks the real Chronos residual detector against the stand-in above, and is
skipped automatically where the model is not installed. Two things came out of
it worth keeping:

- The real residuals track the stand-in closely across every failure mode
  (spike 7.8 against 7.9 sigma, drift 4.4 against 4.5), so weights tuned against
  the stand-in hold for the real detector rather than merely being conservative.
- On a frozen sensor the real residual is **exactly 0.00 sigma**. A flat series
  predicts itself perfectly, so the detector reports maximum agreement precisely
  when the instrument has died. That is the measurement the flatline detector
  exists because of, rather than an argument someone made.

It also prices the horizon cache: a six-step forecast costs about the same as a
one-step forecast (269 ms against 249 ms), so caching one across the horizon it
covers turns ~250 ms per reading into ~45 ms.

## flatline_validation

Answers the one question the per-kind table cannot: can the stuck-sensor
detector tell a legitimately stable physical condition from an unnaturally
constant one? Synthetic flat sequences do not test that, and the first version
of the detector scored 100% on them while being unusable.

Measured against realistic stationary conditions it flagged a **working**
instrument in very calm conditions **100%** of the time, and a calm night 85%
of the time — it would have fired continuously on exactly the quiet nights when
nothing was wrong. The cause is quantisation: instruments report in steps, so a
settled environment produces long runs of bit-identical values. Over 4,000
readings from a healthy station the longest identical run per channel was:

    temperature 33   humidity 11   airQuality 82   pressure 405   oxygen 4000

Oxygen never changed at all. Its reporting step is larger than how much ambient
oxygen actually moves, so no fixed threshold and no cross-channel rule can
work: "flat while the others move" describes a frozen thermometer and a healthy
oxygen sensor equally well.

The detector now learns each channel's normal run length and reports a break in
that channel's own behaviour. Current numbers: **0% false alarms** across every
stationary regime, alerts **+30 readings** after a whole-device freeze, and
between +30 and +429 for a single failed channel — the slow cases being exactly
the channels that legitimately sit still for a long time.

Its known blind spot is reported by the harness rather than hidden: a channel
frozen before the service ever saw it change has no established normal to
break, and is genuinely indistinguishable from one whose quantity does not
move. An absolute run limit was tried and removed, because it flagged every
healthy oxygen sensor after two days.

**Note on the generator.** These use a mean-reverting process, not a random
walk. A plain walk with calm=0.25 wanders about 19 degrees over 4,000 readings
and leaves the rule engine's physical bounds, so an earlier version of this
harness was counting rule breaches caused by its own generator and reporting
them as flatline false alarms.

## field_evaluation

The only harness that takes real data. Give it a telemetry export and it
reports the same measures as the synthetic suite, broken down the way the
question actually needs:

- **Observed instrument behaviour** — the reporting step and run-length
  distribution read back off the data, against the modelled ones. This is the
  direct test of whether the tuning transfers: a channel whose observed step
  differs from the modelled one has run-length thresholds chosen for a
  different instrument.
- **Flag rate by operating regime**, split between devices with a known fault
  and devices without. One sustained freeze contributes hundreds of correctly
  flagged readings, which is not hundreds of false alarms, and only the
  no-known-fault table is a false-positive rate.
- **Detection delay** per fault, when a fault list is supplied.
- **Score calibration** — the distribution and how much headroom sits between
  ordinary readings and the threshold.

Without a fault list it reports flag rates and lists the episodes it raised,
explicitly as candidates. Unlabelled data is not evidence of health, so it does
not call an unadjudicated flag a false positive.

## model_sizing

Latency against detection quality across forest sizes. The forest is the
slowest thing in the scoring path by an order of magnitude, and its cost is
almost entirely a fixed per-call cost proportional to the tree count — measured,
scoring one reading costs about as much as scoring a hundred. Since every
reading is scored individually, the tree count is the main throughput lever.

## performance

Eight sections, reporting latency percentiles rather than means: the tail is
what an operator experiences when every reading from every device is scored
continuously.

Every measurement is min-of-N repeats around a warmed path. This matters more
than it sounds: an earlier single-run version of this harness reported the same
refactor as 1.49x faster and 0.82x slower on consecutive runs, which is exactly
how a change with no effect gets shipped as an optimisation. Two "optimisations"
were reverted after being measured properly — a single-pass report builder that
was 2x slower than the five-pass version it replaced, because `min`, `max` and
`sum` iterate in C while a hand-rolled accumulator loop iterates in bytecode.

### What profiling found

The cost was concentrated in one place, and not where the work was:

| detector | before | after |
|---|---|---|
| rule engine | 1.7 us | 1.7 us |
| flatline (stateful) | 2.0 us | 2.0 us |
| scorer combine | 1.8 us | 2.7 us |
| **Isolation Forest** | **1,143 us** | **140 us** |
| end-to-end, cached forecast | 1,362 us | 244 us |

Almost none of the forest's cost was tree traversal. Scoring one reading asks
each of 50 estimators for a leaf index, and scikit-learn's `tree.apply` re-runs
`check_is_fitted` and a feature-count check *per tree, per reading* — fifty
rounds of Python bookkeeping around fifty sub-microsecond Cython calls. Doing
that arithmetic directly is ~8x faster end-to-end.

The risk in that is silent divergence, not slowness: it depends on library
internals, and a version that reorganised them could leave a detector returning
plausible numbers that are no longer the model that was trained and calibrated.
So the fast path is verified against `Pipeline.decision_function` when it is
built and declines to install itself on any mismatch — a future scikit-learn
costs speed, never correctness. `test_fast_scoring.py` holds it to exact
equality rather than a tolerance.

Fuzzing found the one input class where the two paths disagreed: a reading
finite as a float64 but past the float32 range the trees use, where the library
refuses and the fast path had been scoring it 1.0. Both now refuse, and the
build-time check covers that case.

## capacity

`performance` measures components; this measures the decision — how many
stations one container carries, at what reporting interval, and **which thing
saturates first**, since that determines what to change.

Measured on the shipped configuration:

| | |
|---|---|
| full hybrid scoring | **168 us/reading** (~6,000/sec single-threaded) |
| one batched Chronos forecast | **67 ms**, all five features in one forward pass |
| amortised over a horizon of 6 | 11.1 ms/reading — **87% of per-reading cost** |
| sustained worker throughput | **78 readings/sec/thread**, 6.8M/day |
| per-device state | **20.5 KiB**, window capped at 48 readings |

The bottleneck is not CPU and not memory. At a 30-second reporting interval one
thread could carry ~2,300 devices, and 500 is where `AI_STREAM_MAX_DEVICES`
stops it — at roughly 10 MiB of per-device state. Raising that cap is the
cheapest capacity available: 10,000 devices is about 200 MiB. Past that, the
transformer sets the limit, and the lever is the forecast horizon rather than
the core count, because one call covers `horizon` readings and its cost divides
by that number.

Eviction is least-recently-used everywhere, so an over-capacity fleet sheds the
devices that stopped reporting rather than the ones that never stop. An evicted
device is not scored wrongly afterwards — it is *blind* until it warms up
again, because the flatline detector's baseline has to be re-learned.

## workloads

Shared seeded data. The five anomaly kinds are the failure modes an
environmental sensor actually has, and they are deliberately of different
difficulty:

| kind | what it is | who catches it |
|---|---|---|
| `out_of_range` | outside the instrument's physical envelope | rules |
| `spike` | sudden jump, still inside bounds | forecast residual |
| `multivariate` | every value plausible, the combination not | Isolation Forest, and nothing else |
| `drift` | slow shift away from the trained distribution | residual, forest partially |
| `stuck` | instrument repeating one value | flatline detector |

`stuck` is the one worth understanding. A frozen sensor does not merely evade
the other three detectors — it looks *healthier* than a working one. A flat
series predicts itself perfectly, so the residual detector reports maximum
agreement exactly when the instrument has died. That is why the flatline
detector is weighted above the threshold and can raise an anomaly alone.

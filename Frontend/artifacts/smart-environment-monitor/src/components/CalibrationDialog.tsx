import { useEffect, useState, type FormEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { calibrationApi, type CalibrationInput } from '@/api/sensorApi';
import type { Sensor } from '@/types/domain';
import { UNKNOWN } from '@/lib/format';

function parse(value: string): number | null {
  if (!value.trim()) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function show(value: number | null, digits = 3): string {
  return value === null ? UNKNOWN : value.toFixed(digits).replace(/\.?0+$/, '') || '0';
}

/**
 * The calibration workflow:
 *
 *   raw value  ->  + offset  ->  corrected value
 *   offset      =  reference - sensor, read side by side
 *
 * The operator enters what the reference instrument reads; by default the
 * sensor side is the station's latest raw reading, which the service pairs it
 * with. The offset is computed by the service, never typed in, and a value it
 * finds implausible comes back with its reason, shown here verbatim.
 *
 * Never retried automatically: a retried POST would be a second calibration.
 */
export function CalibrationDialog({ sensor, open, onOpenChange }: {
  sensor: Sensor | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [reference, setReference] = useState('');
  const [sensorValue, setSensorValue] = useState('');
  const [calibratedAt, setCalibratedAt] = useState('');
  const [notes, setNotes] = useState('');

  useEffect(() => {
    if (open) { setReference(''); setSensorValue(''); setCalibratedAt(''); setNotes(''); }
  }, [open, sensor?.stationUid, sensor?.type]);

  const mutation = useMutation({
    mutationFn: (input: CalibrationInput) => calibrationApi.record(sensor!.stationUid, input),
    retry: false,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sensors'] });
      onOpenChange(false);
    },
  });

  if (!sensor) return null;

  const referenceNumber = parse(reference);
  const explicitSensor = parse(sensorValue);
  // Without an explicit value, the preview pairs with the raw reading on screen;
  // the service pairs with the newest one it holds, which may be a second newer.
  const pairedWith = explicitSensor ?? sensor.rawValue ?? null;
  const offset = referenceNumber !== null && pairedWith !== null ? referenceNumber - pairedWith : null;
  const backdated = calibratedAt.trim() !== '';
  const needsSensorValue = backdated && explicitSensor === null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (referenceNumber === null || needsSensorValue) return;
    mutation.mutate({
      sensorType: sensor.type!,
      referenceValue: referenceNumber,
      ...(explicitSensor !== null ? { sensorValue: explicitSensor } : {}),
      // datetime-local has no zone; the service stores UTC.
      ...(backdated ? { calibratedAt: new Date(calibratedAt).toISOString().replace('Z', '') } : {}),
      ...(notes.trim() ? { notes: notes.trim() } : {}),
    });
  };

  return (
    <Dialog.Root open={open} onOpenChange={(next) => { if (!mutation.isPending) onOpenChange(next); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="confirm-overlay" />
        <Dialog.Content className="confirm-dialog" data-testid="calibration-dialog">
          <Dialog.Title className="confirm-title">Calibrate {sensor.name.toLowerCase()}</Dialog.Title>
          <Dialog.Description className="confirm-description">
            {sensor.stationUid}. Read a reference instrument beside the station and enter its value. The difference from
            the sensor becomes an offset applied to every reading from now on; raw values are always kept.
          </Dialog.Description>

          <form className="calibration-form" onSubmit={submit}>
            <div className="field">
              <label htmlFor="cal-reference">Reference reading ({sensor.unit.trim()})</label>
              <input id="cal-reference" inputMode="decimal" value={reference} onChange={(e) => setReference(e.target.value)} required autoFocus data-testid="input-calibration-reference" />
            </div>

            <div className="calibration-preview" aria-live="polite">
              <div><small>Sensor (raw)</small><strong>{show(pairedWith, 2)}</strong></div>
              <div><small>Offset</small><strong>{offset === null ? UNKNOWN : `${offset >= 0 ? '+' : ''}${show(offset)}`}</strong></div>
              <div><small>Corrected</small><strong>{show(referenceNumber, 2)}</strong></div>
            </div>
            {explicitSensor === null && (
              <small className="panel-meta" style={{ marginTop: 'calc(-1 * var(--space-2))' }}>
                Preview from the reading on screen. The service pairs the reference with the station's newest raw
                reading, so the recorded offset can differ slightly.
              </small>
            )}

            <details>
              <summary className="panel-meta">Recording a calibration done earlier?</summary>
              <div className="calibration-form" style={{ marginTop: 'var(--space-3)' }}>
                <div className="field">
                  <label htmlFor="cal-sensor">Sensor reading at the time ({sensor.unit.trim()})</label>
                  <input id="cal-sensor" inputMode="decimal" value={sensorValue} onChange={(e) => setSensorValue(e.target.value)} />
                  <small>Leave empty to pair with the sensor's latest raw reading.</small>
                </div>
                <div className="field">
                  <label htmlFor="cal-when">Calibrated at</label>
                  <input id="cal-when" type="datetime-local" value={calibratedAt} onChange={(e) => setCalibratedAt(e.target.value)} />
                  {needsSensorValue && <small className="warn-text">A past calibration needs the sensor's reading from that time.</small>}
                </div>
              </div>
            </details>

            <div className="field">
              <label htmlFor="cal-notes">Notes</label>
              <textarea id="cal-notes" maxLength={500} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Reference instrument, method, conditions" />
            </div>

            {mutation.isError && (
              <div className="form-error" role="alert" data-testid="calibration-error">
                {mutation.error instanceof Error ? mutation.error.message : 'The calibration was not recorded.'}
              </div>
            )}

            <div className="confirm-actions">
              <Dialog.Close asChild>
                <button type="button" className="button button-secondary" disabled={mutation.isPending}>Cancel</button>
              </Dialog.Close>
              <button type="submit" className="button button-primary" disabled={mutation.isPending || referenceNumber === null || needsSensorValue} data-testid="button-calibration-submit">
                {mutation.isPending ? 'Recording…' : 'Record calibration'}
              </button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

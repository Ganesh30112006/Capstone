import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Where the person looking at the map is, when they choose to say.
 *
 * The states are kept apart because they mean different things to the operator
 * and only one of them is worth acting on. "Denied" is a decision they made and
 * no amount of retrying will change it; "unavailable" is the device failing to
 * get a fix and may well work a moment later; "idle" is the ordinary state of
 * not having asked. Collapsing them into "no location" would leave a Locate
 * button that appears to do nothing for a reason the operator cannot see.
 *
 * Nothing is requested until asked for. A console that demands geolocation on
 * load gets refused once and then cannot ask again.
 */
export type ViewerLocationStatus =
  | 'idle'
  | 'locating'
  | 'ready'
  | 'denied'
  | 'unavailable'
  | 'unsupported';

export interface ViewerLocation {
  status: ViewerLocationStatus;
  position: [number, number] | null;
  /** Reported accuracy radius in metres; null when the browser omits it. */
  accuracyMetres: number | null;
  /** When the fix was taken, not when it was rendered. */
  fixedAt: string | null;
  message: string | null;
  request: () => void;
  clear: () => void;
}

const MESSAGES: Record<Exclude<ViewerLocationStatus, 'idle' | 'locating' | 'ready'>, string> = {
  denied: 'Location permission was declined, so your position cannot be shown. Allow location for this site in your browser to use it.',
  unavailable: 'Your device could not determine a position. This is usually temporary — indoors or without GPS it can take a moment.',
  unsupported: 'This browser does not provide geolocation, so your position cannot be shown.',
};

export function useViewerLocation(): ViewerLocation {
  const [status, setStatus] = useState<ViewerLocationStatus>('idle');
  const [position, setPosition] = useState<[number, number] | null>(null);
  const [accuracyMetres, setAccuracy] = useState<number | null>(null);
  const [fixedAt, setFixedAt] = useState<string | null>(null);
  const watchRef = useRef<number | null>(null);

  const stopWatching = useCallback(() => {
    if (watchRef.current !== null && typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.clearWatch(watchRef.current);
    }
    watchRef.current = null;
  }, []);

  useEffect(() => stopWatching, [stopWatching]);

  const request = useCallback(() => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setStatus('unsupported');
      return;
    }
    setStatus('locating');
    stopWatching();

    watchRef.current = navigator.geolocation.watchPosition(
      (fix) => {
        setPosition([fix.coords.latitude, fix.coords.longitude]);
        // Accuracy travels with the fix so the map can draw the uncertainty
        // rather than a point that claims more precision than the device has.
        setAccuracy(Number.isFinite(fix.coords.accuracy) ? fix.coords.accuracy : null);
        setFixedAt(new Date(fix.timestamp).toISOString());
        setStatus('ready');
      },
      (error) => {
        stopWatching();
        setPosition(null);
        setAccuracy(null);
        setFixedAt(null);
        setStatus(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable');
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 30_000 },
    );
  }, [stopWatching]);

  const clear = useCallback(() => {
    stopWatching();
    setPosition(null);
    setAccuracy(null);
    setFixedAt(null);
    setStatus('idle');
  }, [stopWatching]);

  return {
    status,
    position,
    accuracyMetres,
    fixedAt,
    message: status === 'idle' || status === 'locating' || status === 'ready' ? null : MESSAGES[status],
    request,
    clear,
  };
}

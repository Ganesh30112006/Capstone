import type { ReactNode } from 'react';
import { AlertOctagon, RefreshCw } from 'lucide-react';
import { UNKNOWN } from '@/lib/format';

/**
 * The console's answer to a single question: is this panel empty, or is it
 * merely unable to say?
 *
 * Every list in this application used to render the same way in both cases,
 * because the api/* layer caught failures and returned `[]`. A gateway that
 * could not be reached produced an empty fleet, an empty alert queue, an empty
 * account list — each of which reads as a confident statement about the
 * system. `DataState` makes the four states the UI can legitimately be in
 * explicit, and refuses to let the third one impersonate the fourth:
 *
 *   pending      we have not asked yet
 *   unavailable  we asked and could not find out
 *   empty        we asked, and the answer is genuinely nothing
 *   ready        we asked, and here is the answer
 */

/** Structurally compatible with TanStack's `UseQueryResult`. */
export interface QueryLike<T> {
  data: T | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  refetch?: () => unknown;
}

/** A short, non-technical account of why a request did not complete. */
export function describeFailure(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return 'The request did not complete.';
}

function defaultIsEmpty(data: unknown): boolean {
  if (data === null || data === undefined) return true;
  if (Array.isArray(data)) return data.length === 0;
  return false;
}

export function UnavailableNotice({
  what,
  error,
  onRetry,
  detail,
}: {
  /** What could not be read, as a noun phrase: "the fleet", "sensor diagnostics". */
  what: string;
  error?: unknown;
  onRetry?: () => unknown;
  /** Replaces the default explanation where a page needs a more specific one. */
  detail?: string;
}) {
  return (
    <div className="callout bad unavailable-notice" role="alert" data-testid="notice-unavailable">
      <AlertOctagon size={17} />
      <div>
        <strong data-testid="notice-unavailable-title">{what} could not be loaded</strong>
        <p>
          {sentence(detail ?? describeFailure(error))} Nothing below has been confirmed — this is not the
          same as there being none.
        </p>
      </div>
      {onRetry && (
        <button className="button button-secondary" onClick={() => onRetry()} data-testid="button-retry-unavailable">
          <RefreshCw size={14} />
          Try again
        </button>
      )}
    </div>
  );
}

export function DataState<T>({
  query,
  what,
  empty,
  pending,
  unavailable,
  isEmpty = defaultIsEmpty,
  children,
}: {
  query: QueryLike<T>;
  /** Noun phrase used in both the loading and the failure message. */
  what: string;
  /** Shown only when the request succeeded and the answer really is nothing. */
  empty: ReactNode;
  pending?: ReactNode;
  /**
   * Replaces the default banner where the page already reports this failure
   * somewhere more prominent, so one outage does not produce four notices.
   * It must still say the data is unknown — never fall back to the empty state here.
   */
  unavailable?: ReactNode;
  isEmpty?: (data: T) => boolean;
  children: (data: T) => ReactNode;
}) {
  if (query.isPending) {
    return <>{pending ?? <div className="empty-inline" data-testid="state-pending">Loading {what}…</div>}</>;
  }
  if (query.isError || query.data === undefined) {
    return <>{unavailable ?? <UnavailableNotice what={capitalise(what)} error={query.error} onRetry={query.refetch} />}</>;
  }
  if (isEmpty(query.data)) {
    return <>{empty}</>;
  }
  return <>{children(query.data)}</>;
}

/** "Authentication expired" ran straight into the next sentence; end it first. */
function sentence(text: string): string {
  const trimmed = text.trim();
  return /[.!?…]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Value for a derived figure — a count, an average, a percentage — computed
 * from a query that may not have answered. A KPI tile reading "0" beside a
 * failure banner contradicts the banner, so derived figures go unknown too.
 */
export function derived<T>(query: QueryLike<T>, compute: (data: T) => string | number): string | number {
  if (query.isPending || query.isError || query.data === undefined) return UNKNOWN;
  return compute(query.data);
}

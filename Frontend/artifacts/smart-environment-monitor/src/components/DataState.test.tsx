import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataState, UnavailableNotice, derived, type QueryLike } from './DataState';

/**
 * These four cases are the whole point of the component, and the third is the
 * one the console used to get wrong: a query that failed rendered through the
 * same branch as a query that succeeded with nothing in it.
 */
function query<T>(overrides: Partial<QueryLike<T>>): QueryLike<T> {
  return { data: undefined, isPending: false, isError: false, error: undefined, ...overrides };
}

describe('DataState', () => {
  it('shows a loading line while the request is in flight', () => {
    render(
      <DataState query={query<string[]>({ isPending: true })} what="the fleet" empty={<p>None</p>}>
        {(data) => <p>{data.join(',')}</p>}
      </DataState>,
    );
    expect(screen.getByTestId('state-pending')).toHaveTextContent(/Loading the fleet/i);
  });

  it('says the data could not be loaded when the request failed', () => {
    render(
      <DataState
        query={query<string[]>({ isError: true, error: new Error('Could not reach the API Gateway.') })}
        what="the fleet"
        empty={<p>None</p>}
      >
        {(data) => <p>{data.join(',')}</p>}
      </DataState>,
    );
    expect(screen.getByTestId('notice-unavailable')).toBeInTheDocument();
    expect(screen.getByText(/Could not reach the API Gateway/i)).toBeInTheDocument();
  });

  it('never renders the empty state for a failed request', () => {
    render(
      <DataState query={query<string[]>({ isError: true, error: new Error('nope') })} what="the fleet" empty={<p>No stations are deployed</p>}>
        {(data) => <p>{data.join(',')}</p>}
      </DataState>,
    );
    // The bug this replaces: a failure fell through to the empty branch and the
    // page stated, as fact, that there were no stations.
    expect(screen.queryByText(/No stations are deployed/i)).not.toBeInTheDocument();
  });

  it('shows the empty state only when the request succeeded with nothing', () => {
    render(
      <DataState query={query<string[]>({ data: [] })} what="the fleet" empty={<p>No stations are deployed</p>}>
        {(data) => <p>{data.join(',')}</p>}
      </DataState>,
    );
    expect(screen.getByText(/No stations are deployed/i)).toBeInTheDocument();
    expect(screen.queryByTestId('notice-unavailable')).not.toBeInTheDocument();
  });

  it('renders the data when there is data', () => {
    render(
      <DataState query={query<string[]>({ data: ['STN-1', 'STN-2'] })} what="the fleet" empty={<p>None</p>}>
        {(data) => <p>{data.join(',')}</p>}
      </DataState>,
    );
    expect(screen.getByText('STN-1,STN-2')).toBeInTheDocument();
  });

  it('treats a null single resource as empty', () => {
    render(
      <DataState query={query<string | null>({ data: null })} what="the report" empty={<p>No report yet</p>}>
        {(data) => <p>{data}</p>}
      </DataState>,
    );
    expect(screen.getByText(/No report yet/i)).toBeInTheDocument();
  });
});

describe('derived', () => {
  it('computes the figure when the query answered', () => {
    expect(derived(query<number[]>({ data: [1, 2, 3] }), (d) => d.length)).toBe(3);
  });

  it('returns an em dash rather than zero when the query failed', () => {
    // A KPI tile reading "0" beside a failure banner contradicts the banner.
    expect(derived(query<number[]>({ isError: true }), (d) => d.length)).toBe('—');
  });

  it('returns an em dash while the query is still pending', () => {
    expect(derived(query<number[]>({ isPending: true }), (d) => d.length)).toBe('—');
  });
});

describe('UnavailableNotice', () => {
  it('offers a retry only when one is available', () => {
    const { rerender } = render(<UnavailableNotice what="The fleet" error={new Error('x')} />);
    expect(screen.queryByTestId('button-retry-unavailable')).not.toBeInTheDocument();

    rerender(<UnavailableNotice what="The fleet" error={new Error('x')} onRetry={() => undefined} />);
    expect(screen.getByTestId('button-retry-unavailable')).toBeInTheDocument();
  });

  it('states plainly that nothing has been confirmed', () => {
    render(<UnavailableNotice what="The alert queue" detail="alert-service did not answer." />);
    expect(screen.getByTestId('notice-unavailable-title')).toHaveTextContent(/The alert queue could not be loaded/i);
    expect(screen.getByText(/not the same as there being none/i)).toBeInTheDocument();
  });
});

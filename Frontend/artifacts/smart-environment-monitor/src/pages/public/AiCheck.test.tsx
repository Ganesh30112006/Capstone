import { verdictOf } from './AiCheck';

describe('the AI check verdict', () => {
  it('says nothing about skill before there is enough evidence', () => {
    expect(verdictOf(undefined, 30).tone).toBe('pending');
    expect(verdictOf({ scored: 0, enough: false }, 30).tone).toBe('pending');
    expect(verdictOf({ scored: 12, enough: false }, 30).word).toBe('Checking: 12 of 30 forecasts scored');
  });

  it('is decided by the interval, not the point estimate', () => {
    expect(verdictOf({ scored: 200, enough: true, meanErrorReductionCi95: [0.01, 0.05] }, 30).tone).toBe('good');
    expect(verdictOf({ scored: 200, enough: true, meanErrorReductionCi95: [-0.05, -0.01] }, 30).tone).toBe('bad');
    // A positive mean whose interval crosses zero is not a win.
    expect(verdictOf({ scored: 200, enough: true, meanErrorReductionCi95: [-0.01, 0.04] }, 30).word).toBe('No clear difference yet');
  });
});

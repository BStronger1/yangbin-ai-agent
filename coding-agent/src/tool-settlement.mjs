// Receipts describe the executor's commit boundary, never inferred from error text.
export function mutationReceipt(value) {
  if (value == null) return null;
  const phases = { NO_EFFECT: ['VALIDATING'], UNKNOWN: ['VALIDATING', 'PREPARING', 'COMMITTING'], COMMITTED: ['COMMITTED'] };
  if (typeof value !== 'object' || !Object.hasOwn(phases, value.effect) || !phases[value.effect].includes(value.phase)) {
    throw new Error('Invalid mutation receipt');
  }
  return { effect: value.effect, phase: value.phase };
}

export function toolOutcome({ ok, sideEffect, dispatched, failure, useReceipts = true }) {
  if (ok) return 'CONFIRMED';
  if (!sideEffect || !dispatched) return 'NO_EFFECT';
  const receipt = useReceipts ? mutationReceipt(failure?.mutationReceipt) : null;
  if (receipt?.effect === 'NO_EFFECT') return 'NO_EFFECT';
  if (receipt?.effect === 'COMMITTED') return 'CONFIRMED';
  return 'UNKNOWN_EFFECT';
}

import { Buffer } from 'node:buffer';
/** Conservative byte reservation; never a claim of billable token estimation. */
export function reserveTrace(requestBytes, outputLimit, cap) {
  if (!Array.isArray(requestBytes) || !Number.isSafeInteger(outputLimit) || outputLimit < 1 || !Number.isSafeInteger(cap) || cap < 1) throw new Error('Invalid trace budget');
  let used = 0;
  return requestBytes.map((bytes, index) => {
    if (!Number.isSafeInteger(bytes) || bytes < 0) throw new Error('Invalid request byte count');
    const reserve = bytes + outputLimit, allowed = used + reserve <= cap;
    if (allowed) used += reserve;
    return { index, reserve, allowed, used, remaining: cap - used };
  });
}
/** Partial summaries never replace retained history. No implicit retry or output-limit escalation. */
export function inspectSummary({ finishReason, outputTokens, outputLimit, visibleText, thinkingChars = 0 }) {
  if (!Number.isSafeInteger(outputTokens) || outputTokens < 0 || !Number.isSafeInteger(outputLimit) || outputLimit < 1 || !Number.isSafeInteger(thinkingChars) || thinkingChars < 0) throw new Error('Invalid summary metadata');
  const accepted = finishReason === 'stop' && typeof visibleText === 'string' && visibleText.trim().length > 0 && outputTokens <= outputLimit;
  return { accepted, reason: accepted ? 'complete' : finishReason === 'length' ? 'summary_output_cap' : 'summary_incomplete', retainOriginalHistory: !accepted, automaticRetry: false, visibleBytes: Buffer.byteLength(visibleText || ''), thinkingChars, outputTokens };
}
export function prospectiveBudget({ contextBytes, outputLimit, maxRequests, globalCap }) {
  if (![contextBytes, outputLimit, maxRequests, globalCap].every(x => Number.isSafeInteger(x) && x > 0)) throw new Error('Invalid envelope');
  const worstCase = (contextBytes + outputLimit) * maxRequests;
  if (!Number.isSafeInteger(worstCase)) throw new Error('Envelope overflow');
  return { worstCase, feasible: worstCase <= globalCap, needsExplicitNewAuthorization: true, realExecutionEnabled: false };
}

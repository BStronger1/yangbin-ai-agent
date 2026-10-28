// DeepSeek Harness f3803c9: 1M context, 256K default output. Explicit gateway
// and account limits still win; output headroom is never an unrelated 8K cap.
export const isDeepSeekV4 = model => /^deepseek-(?:flash|v4(?:\.1)?-(?:flash|pro)(?:-[a-z0-9.-]+)?)$/i.test(model || '');
export function modelBudget(env, { layered = true, memoryEnabled = false } = {}) {
  const deepseek = isDeepSeekV4(env.CODING_MODEL_NAME || env.CODING_MODEL_MODEL);
  const integer = (name, fallback, max) => {
    const raw = env[name]; if (raw == null || raw === '') return fallback;
    const n = Number(raw); if (!Number.isSafeInteger(n) || n < 1 || n > max) throw new Error(`Invalid ${name}`);
    return n;
  };
  const contextWindow = integer('CODING_CONTEXT_WINDOW', deepseek ? 1000000 : 128000, 2000000);
  if (contextWindow < 4096) throw new Error('Context window must be at least 4096');
  const requested = integer('CODING_OUTPUT_TOKENS', deepseek ? Math.min(256000, Math.floor(contextWindow / 2)) : Math.min(32768, Math.floor(contextWindow / 4)), 384000);
  const ceiling = integer('CODING_OUTPUT_CEILING', 384000, 384000);
  const anchors = (layered ? Math.min(2048, Math.floor(contextWindow / 8)) : 0) + (memoryEnabled ? Math.min(2048, Math.floor(contextWindow / 4)) : 0);
  const maxOutputTokens = Math.min(requested, ceiling, contextWindow - anchors - Math.min(4096, Math.floor(contextWindow / 4)));
  if (maxOutputTokens < 1) throw new Error('Context window is too small for input and output headroom');
  return { contextWindow, maxOutputTokens, reserveTokens: Math.max(1024, maxOutputTokens) + anchors };
}

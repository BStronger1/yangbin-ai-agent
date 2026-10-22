// The Java account/model service validates the selected model's supported levels.
// This layer validates the protocol again, and writes the native field after SDK
// serialization but BEFORE ModelGate admission so the final body is budgeted.
const LEVELS = Object.freeze({
  openai: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'],
  deepseek: ['none', 'low', 'high', 'max'],
  anthropic: ['low', 'medium', 'high', 'xhigh', 'max'],
});

export function createReasoningEffort({ provider, api, effort } = {}) {
  const selected = String(effort ?? '').trim();
  if (!selected || selected === 'default') return fetch => fetch;
  const expectedApi = provider === 'anthropic' ? 'anthropic-messages' : 'openai-completions';
  if (!Object.hasOwn(LEVELS, provider) || !LEVELS[provider].includes(selected) || api !== expectedApi) {
    throw new Error('Unsupported reasoning effort for model provider/API');
  }
  return fetch => async (input, init) => {
    if (typeof init?.body !== 'string') throw new Error('Reasoning effort requires a JSON request body');
    let body;
    try { body = JSON.parse(init.body); } catch { throw new Error('Reasoning effort requires a JSON request body'); }
    if (!body || Array.isArray(body) || typeof body !== 'object') throw new Error('Invalid model request body');
    if (provider === 'anthropic') {
      // Effort is separate from thinking. Do not force adaptive thinking on older
      // models (e.g. Opus 4.5), and do not invent an additional token budget.
      body.output_config = { ...body.output_config, effort: selected };
    } else if (provider === 'deepseek') {
      body.thinking = { type: selected === 'none' ? 'disabled' : 'enabled' };
      if (selected === 'none') delete body.reasoning_effort;
      else body.reasoning_effort = selected;
    } else body.reasoning_effort = selected;
    const headers = new Headers(init.headers);
    headers.delete('content-length');
    return fetch(input, { ...init, headers, body: JSON.stringify(body) });
  };
}

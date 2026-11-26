// Page revisions advance per submitted turn, not per execution event.
// Keep projections monotonic when a slow GET snapshot follows a newer SSE state.
export function mergeWorkspaceTurns(previous, incoming) {
  const turns = new Map(previous.map(turn => [turn.id, turn]))
  const terminal = value => ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(value)
  for (const turn of incoming) {
    const current = turns.get(turn.id)
    if (current && ((terminal(current.status) && current.status !== turn.status) || (turn.events?.length || 0) < (current.events?.length || 0) || (turn.artifacts?.length || 0) < (current.artifacts?.length || 0))) continue
    turns.set(turn.id, turn)
  }
  return [...turns.values()]
}


// A missed or out-of-order page needs a fresh session snapshot.
export function mergeRunPage(prior, page) {
  if (!prior || page?.state?.id !== prior.id || !Array.isArray(page.state.events)) return null
  const previous = prior.events || []
  let seq = previous.at(-1)?.seq || 0
  if (page.after !== seq) return null
  for (const event of page.state.events) {
    if (event.seq !== ++seq) return null
  }
  if (page.cursor !== seq) return null
  return {...page.state, events:[...previous, ...page.state.events]}
}

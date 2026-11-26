import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

// Only operation metadata goes into the checkpoint. File content remains in the
// granted workspace and the ordinary SDK session owns conversation history.
export class TurnCheckpoint {
  constructor(directory) {
    this.file = path.join(directory, 'turn-checkpoint.json');
    this.state = { operations: [] };
    this.queue = Promise.resolve();
  }
  async open(recover, originalPrompt = '') {
    let prior = null;
    if (recover) {
      try { prior = JSON.parse(await fs.readFile(this.file, 'utf8')); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    if (prior) this.state = prior;
    else this.state.originalPrompt = originalPrompt;
    await this.persist();
    return prior ? JSON.stringify({originalPrompt:prior.originalPrompt, operations:prior.operations}) : '';
  }
  persist() {
    const body = JSON.stringify(this.state);
    this.queue = this.queue.then(async () => {
      const temp = this.file + '.' + randomUUID();
      try {
        await fs.writeFile(temp, body, {flag:'wx', mode:0o600});
        await fs.rename(temp, this.file);
      } finally { await fs.rm(temp, {force:true}); }
    });
    return this.queue;
  }
  async begin(id, tool, args) {
    if (this.state.operations.length >= 160) throw new Error('Checkpoint operation limit reached');
    this.state.operations.push({ id, tool, path: args.path, status: 'unconfirmed' });
    await this.persist();
  }
  async finish(id, outcome) {
    this.state.operations.find(entry => entry.id === id).status = outcome==='CONFIRMED' ? 'confirmed' : outcome==='NO_EFFECT' ? 'no_effect' : 'unconfirmed';
    await this.persist();
  }
}

export async function resumeTransport({ current, resume, signal, canResume, wait = delay }) {
  for (let attempt = 1; attempt <= 2; attempt++) {
    signal.throwIfAborted();
    const message = current();
    if (message?.stopReason !== 'error' ||
        !canResume()) return;
    await wait(1000 * attempt, undefined, {signal});
    await resume(attempt);
  }
}

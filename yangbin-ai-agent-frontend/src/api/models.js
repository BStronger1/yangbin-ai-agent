import { api } from '../workspace-api'
const data = request => request.then(response => response.data)
export const modelApi = {
  createConnection: input => data(api.post('/ai/models/connections', input)),
  updateConnection: (id,input) => data(api.put(`/ai/models/connections/${encodeURIComponent(id)}`, input)),
  removeConnection: id => data(api.delete(`/ai/models/connections/${encodeURIComponent(id)}`)),
  discover: id => data(api.post(`/ai/models/connections/${encodeURIComponent(id)}/discover`)),
  select: modelProfileIds => data(api.put('/ai/models/selection', { modelProfileIds })),
  list: () => data(api.get('/ai/models')),
  create: input => data(api.post('/ai/models/profiles', input)),
  update: (id, input) => data(api.put(`/ai/models/profiles/${encodeURIComponent(id)}`, input)),
  remove: id => data(api.delete(`/ai/models/profiles/${encodeURIComponent(id)}`)),
  setDefault: modelProfileId => data(api.put('/ai/models/defaults', { modelProfileId }))
}

import { api } from '../workspace-api'
const root = projectId => `/ai/coding/projects/${encodeURIComponent(projectId)}/memory`
const data = request => request.then(response => response.data)
export const memoryApi = {
  list: projectId => data(api.get(root(projectId))),
  search: (projectId, q) => data(api.get(`${root(projectId)}/search`, { params: { q } })),
  create: (projectId, payload) => data(api.post(root(projectId), payload)),
  update: (projectId, id, payload) => data(api.put(`${root(projectId)}/${encodeURIComponent(id)}`, payload)),
  forget: (projectId, id, version) => data(api.delete(`${root(projectId)}/${encodeURIComponent(id)}`, { params: { version } })),
  settings: (projectId, payload) => data(api.put(`${root(projectId)}/settings`, payload))
}

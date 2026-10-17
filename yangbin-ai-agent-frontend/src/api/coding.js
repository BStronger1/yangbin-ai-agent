import { api } from '../workspace-api'
const root = '/ai/coding'
const data = request => request.then(response => response.data)
export const codingApi = {
  capabilities: modelProfileId => data(api.get(`${root}/capabilities`, { params: modelProfileId ? { modelProfileId } : {} })),
  projects: () => data(api.get(`${root}/projects`)),
  createProject: name => data(api.post(`${root}/projects`, { name })),
  files: id => data(api.get(`${root}/projects/${encodeURIComponent(id)}/files`)),
  file: (id, path) => data(api.get(`${root}/projects/${encodeURIComponent(id)}/file`, { params: { path } })),
  putFile: (id, path, content) => data(api.put(`${root}/projects/${encodeURIComponent(id)}/file`, { path, content })),
  sessions: () => data(api.get(`${root}/sessions`)),
  createSession: payload => data(api.post(`${root}/sessions`, payload)),
  session: id => data(api.get(`${root}/sessions/${encodeURIComponent(id)}`)),
  turn: (id, payload) => data(api.post(`${root}/sessions/${encodeURIComponent(id)}/turns`, payload)),
  approve: (id, approvalId, approved) => data(api.post(`${root}/sessions/${encodeURIComponent(id)}/approvals/${encodeURIComponent(approvalId)}`, { approved })),
  cancel: id => data(api.post(`${root}/sessions/${encodeURIComponent(id)}/cancel`)),
  diff: id => data(api.get(`${root}/sessions/${encodeURIComponent(id)}/diff`))
}

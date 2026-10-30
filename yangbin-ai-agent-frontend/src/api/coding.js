import { api } from '../workspace-api'
const root = '/ai/coding'
const data = request => request.then(response => response.data)
export const codingApi = {
  capabilities: modelProfileId => data(api.get(`${root}/capabilities`, { params: modelProfileId ? { modelProfileId } : {} })),
  projects: () => data(api.get(`${root}/projects`)),
  recovery: id => data(api.get(`${root}/projects/${encodeURIComponent(id)}/recovery`)),
  acceptRecovery: (id, token) => data(api.post(`${root}/projects/${encodeURIComponent(id)}/recovery/accept`, {token, acceptCurrentFiles:true})),
  createProject: (name, source = "server") => data(api.post(`${root}/projects`, { name, source })),
  localRequests: (id, clientId) => data(api.get(`${root}/sessions/${encodeURIComponent(id)}/local-files`, { headers: { "X-Local-Client": clientId } })),
  claimLocal: (id, clientId, requestId) => data(api.post(`${root}/sessions/${encodeURIComponent(id)}/local-files/claim`, { clientId, requestId })),
  completeLocal: (id, result) => data(api.post(`${root}/sessions/${encodeURIComponent(id)}/local-files/result`, result)),
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

import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const berth = {
  board: () => call(http.get('/berth/board')),
  list: () => call(http.get('/berth/list')),
  get: (id) => call(http.get(`/berth/${id}`)),
  create: (data) => call(http.post('/berth', data)),
  update: (id, data) => call(http.put(`/berth/${id}`, data)),
  occupy: (id, vesselId) => call(http.post(`/berth/${id}/occupy?vesselId=${vesselId}`)),
  release: (id) => call(http.post(`/berth/${id}/release`)),
  move: (data) => call(http.post('/berth/move', data)),
  status: (id, status) => call(http.post(`/berth/${id}/status?status=${encodeURIComponent(status)}`)),
  remove: (id) => call(http.delete(`/berth/${id}`))
}

const vessel = {
  board: () => call(http.get('/vessel/board')),
  list: () => call(http.get('/vessel/list')),
  get: (id) => call(http.get(`/vessel/${id}`)),
  detail: (id) => call(http.get(`/vessel/${id}/detail`)),
  create: (data) => call(http.post('/vessel', data)),
  update: (id, data) => call(http.put(`/vessel/${id}`, data)),
  remove: (id) => call(http.delete(`/vessel/${id}`)),

  contractBoard: () => call(http.get('/vessel/contracts/board')),
  contracts: () => call(http.get('/vessel/contracts/list')),
  contract: (id) => call(http.get(`/vessel/contracts/${id}`)),
  createContract: (data) => call(http.post('/vessel/contracts', data)),
  updateContract: (id, data) => call(http.put(`/vessel/contracts/${id}`, data)),
  advanceContract: (id) => call(http.post(`/vessel/contracts/${id}/advance`)),
  removeContract: (id) => call(http.delete(`/vessel/contracts/${id}`)),
  contractPayment: (id) => call(http.get(`/vessel/contracts/${id}/payment`)),
  confirmPayment: (nodeId, operator) =>
    call(http.post(`/vessel/contracts/payments/${nodeId}/confirm?operator=${encodeURIComponent(operator || '')}`)),

  owners: () => call(http.get('/vessel/owners')),
  createOwner: (data) => call(http.post('/vessel/owners', data)),
  updateOwner: (id, data) => call(http.put(`/vessel/owners/${id}`, data)),
  removeOwner: (id) => call(http.delete(`/vessel/owners/${id}`))
}

const block = {
  board: () => call(http.get('/block/board')),
  list: () => call(http.get('/block/list')),
  get: (id) => call(http.get(`/block/${id}`)),
  detail: (id) => call(http.get(`/block/${id}/detail`)),
  create: (data) => call(http.post('/block', data)),
  update: (id, data) => call(http.put(`/block/${id}`, data)),
  advance: (id) => call(http.post(`/block/${id}/advance`)),
  paint: (id) => call(http.post(`/block/${id}/paint`)),
  remove: (id) => call(http.delete(`/block/${id}`))
}

const erection = {
  board: () => call(http.get('/erection/board')),
  list: () => call(http.get('/erection/list')),
  get: (id) => call(http.get(`/erection/${id}`)),
  detail: (id) => call(http.get(`/erection/${id}/detail`)),
  create: (data) => call(http.post('/erection', data)),
  update: (id, data) => call(http.put(`/erection/${id}`, data)),
  advance: (id) => call(http.post(`/erection/${id}/advance`)),
  remove: (id) => call(http.delete(`/erection/${id}`)),
  lifts: () => call(http.get('/erection/lifts')),
  registerLift: (id, data) => call(http.post(`/erection/${id}/lifts`, data)),
  startLift: (liftId, data) => call(http.post(`/erection/lifts/${liftId}/start`, data)),
  completeLift: (liftId, data) => call(http.post(`/erection/lifts/${liftId}/complete`, data)),
  cranes: () => call(http.get('/erection/cranes')),
  createCrane: (data) => call(http.post('/erection/cranes', data)),
  updateCrane: (id, data) => call(http.put(`/erection/cranes/${id}`, data)),
  removeCrane: (id) => call(http.delete(`/erection/cranes/${id}`))
}

// 起重设备检修状态 ↔ 吊装排程联动
const schedule = {
  impact: (craneId) => call(http.get(`/crane-schedule/cranes/${craneId}/impact`)),
  craneDetail: (craneId) => call(http.get(`/crane-schedule/cranes/${craneId}/detail`)),
  alternatives: (taskId) => call(http.get(`/crane-schedule/tasks/${taskId}/alternatives`)),
  unavailable: (craneId, data) => call(http.post(`/crane-schedule/cranes/${craneId}/unavailable`, data)),
  recover: (craneId, operator) =>
    call(http.post(`/crane-schedule/cranes/${craneId}/recover?operator=${encodeURIComponent(operator || '')}`)),
  reviewPending: () => call(http.get('/crane-schedule/review-pending')),
  review: (taskId, data) => call(http.post(`/crane-schedule/tasks/${taskId}/review`, data)),
  reassign: (taskId, data) => call(http.post(`/crane-schedule/tasks/${taskId}/reassign`, data))
}

const survey = {
  board: () => call(http.get('/survey/board')),
  list: () => call(http.get('/survey/list')),
  get: (id) => call(http.get(`/survey/${id}`)),
  detail: (id) => call(http.get(`/survey/${id}/detail`)),
  create: (data) => call(http.post('/survey', data)),
  update: (id, data) => call(http.put(`/survey/${id}`, data)),
  remove: (id) => call(http.delete(`/survey/${id}`)),
  punchItems: () => call(http.get('/survey/punch-items')),
  createPunch: (data) => call(http.post('/survey/punch-items', data)),
  updatePunch: (id, data) => call(http.put(`/survey/punch-items/${id}`, data)),
  advancePunch: (id) => call(http.post(`/survey/punch-items/${id}/advance`)),
  removePunch: (id) => call(http.delete(`/survey/punch-items/${id}`))
}

const launch = {
  board: () => call(http.get('/launch/board')),
  list: () => call(http.get('/launch/list')),
  ofVessel: (vesselId) => call(http.get(`/launch/vessel/${vesselId}`)),
  detail: (id) => call(http.get(`/launch/${id}`)),
  generate: (vesselId, operator) =>
    call(http.post(`/launch/generate?vesselId=${vesselId}&operator=${encodeURIComponent(operator)}`)),
  recheck: (id, operator) => call(http.post(`/launch/${id}/recheck?operator=${encodeURIComponent(operator)}`)),
  sign: (id, operator) => call(http.post(`/launch/${id}/sign?operator=${encodeURIComponent(operator)}`)),
  confirm: (id, operator) => call(http.post(`/launch/${id}/confirm?operator=${encodeURIComponent(operator)}`)),
  void: (id, operator, reason) =>
    call(http.post(`/launch/${id}/void?operator=${encodeURIComponent(operator)}&reason=${encodeURIComponent(reason || '')}`)),
  regenerate: (id, operator, reason) =>
    call(http.post(`/launch/${id}/regenerate?operator=${encodeURIComponent(operator)}&reason=${encodeURIComponent(reason || '')}`))
}

const stats = {
  overview: () => call(http.get('/stats/overview')),
  dict: () => call(http.get('/stats/dict')),
  berth: () => call(http.get('/stats/berth')),
  block: () => call(http.get('/stats/block')),
  lift: () => call(http.get('/stats/lift')),
  survey: () => call(http.get('/stats/survey')),
  contract: () => call(http.get('/stats/contract')),
  vessel: () => call(http.get('/stats/vessel'))
}

export default { http, berth, vessel, block, erection, schedule, survey, launch, stats }

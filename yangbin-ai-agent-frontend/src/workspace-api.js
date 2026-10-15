import axios from 'axios'
export const api=axios.create({baseURL:import.meta.env.VITE_API_BASE_URL||'/api',timeout:180000})
export const errorMessage=e=>e.response?.data?.message||'请求未完成，请稍后重试'

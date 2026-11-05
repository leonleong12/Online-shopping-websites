import { createApp } from 'vue'
import axios from 'axios'
import App from './App.vue'
import './theme.css'
import './style.css'
import './order-detail.css'
import './site-settings.css'
import './wallet.css'
import './theme-overrides.css'
import './editorial.css'

axios.defaults.baseURL = import.meta.env.VITE_API_BASE || '/api'
axios.interceptors.request.use(config => {
  const token = localStorage.getItem('shop_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
axios.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('shop_token')
      localStorage.removeItem('shop_user')
      window.dispatchEvent(new CustomEvent('shop:auth-expired'))
    }
    return Promise.reject(error)
  }
)
createApp(App).provide('api', axios).mount('#app')

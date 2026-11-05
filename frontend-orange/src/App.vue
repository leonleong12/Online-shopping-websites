<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { createPaymentClock } from './paymentClock.js'
import { formatUtcTime } from './dateTime.js'
import ProductCard from './ProductCard.vue'
import FulfillmentInfo from './FulfillmentInfo.vue'
import WalletPanel from './WalletPanel.vue'
import AdminWallet from './AdminWallet.vue'
import SupportWidget from './SupportWidget.vue'
import AdminSupport from './AdminSupport.vue'
import AdminCardKeys from './AdminCardKeys.vue'
import AdminCategories from './AdminCategories.vue'
const api = inject('api')
const products = ref([]), categories = ref([]), cart = ref([]), orders = ref([])
const dataError = ref('')
const featuredProducts = ref([]), featuredBusy = ref([])
const featuredSection = ref(null)
const adminProducts = ref([]), adminOrders = ref([]), overview = ref({}), sales = ref([]), topProducts = ref([]), statsPeriod = ref('day'), statsBusy = ref(false)
const statsTotals = computed(() => sales.value.reduce((totals, row) => ({
  orders: totals.orders + (Number(row.order_count) || 0),
  items: totals.items + (Number(row.item_count) || 0),
  amount: totals.amount + (Number(row.sales_amount) || 0)
}), { orders: 0, items: 0, amount: 0 }))
const statsSeries = computed(() => sales.value.slice().reverse())
const statsPeak = computed(() => Math.max(1, ...statsSeries.value.map(row => Number(row.sales_amount) || 0)))
const statsChartMax = computed(() => Math.max(1, Math.ceil(statsPeak.value / 4) * 4))
const statsChartPoints = computed(() => {
  const rows = statsSeries.value
  const left = 54, right = 704, top = 18, bottom = 204
  return rows.map((row, index) => {
    const x = rows.length < 2 ? (left + right) / 2 : left + (right - left) * index / (rows.length - 1)
    const value = Number(row.sales_amount) || 0
    const y = bottom - (value / statsChartMax.value) * (bottom - top)
    return { ...row, x, y, value }
  })
})
const statsChartMarkerPoints = computed(() => {
  const points = statsChartPoints.value
  if (points.length <= 8) return points
  const step = Math.ceil((points.length - 1) / 6)
  return points.filter((_, index) => index === 0 || index === points.length - 1 || index % step === 0)
})
const statsChartLine = computed(() => statsChartPoints.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const statsChartArea = computed(() => {
  const points = statsChartPoints.value
  if (!points.length) return ''
  return `${statsChartLine.value} L ${points.at(-1).x} 204 L ${points[0].x} 204 Z`
})
const statsChartTicks = computed(() => Array.from({ length: 5 }, (_, index) => ({
  y: 204 - index * 46.5,
  value: statsChartMax.value * index / 4
})))
const statsChartLabels = computed(() => {
  const points = statsChartPoints.value
  if (!points.length) return []
  return [...new Set([0, Math.round((points.length - 1) / 4), Math.round((points.length - 1) / 2), Math.round((points.length - 1) * 3 / 4), points.length - 1])]
    .map(index => ({ x: points[index].x, label: statsPeriod.value === 'month' ? points[index].period_start.slice(0, 7) : points[index].period_start.slice(5, 10) }))
})
const statsWindowLabel = computed(() => statsPeriod.value === 'day' ? '最近 30 天' : statsPeriod.value === 'week' ? '最近 12 周' : '最近 12 个月')
const adminCategories = ref([])
const user = ref(JSON.parse(localStorage.getItem('shop_user') || 'null'))
const view = ref('store'), search = ref(''), categoryId = ref(null), loading = ref(false), toast = ref(''), mobileMenu = ref(false), cartBusyProducts = ref([])
const authMode = ref('login'), authForm = ref({ username: '', email: '', password: '', newPassword: '', confirmPassword: '', resetToken: '' }), authErrors = ref({})
const productForm = ref(blankProduct()), editingProduct = ref(false), activeProduct = ref(null), productImageFile = ref(null), productImagePreview = ref('')
const siteSettings = ref({ heroImageUrl: '', editorialImageUrl: '' })
const siteForm = ref({ heroImageUrl: '', editorialImageUrl: '' })
const siteImageFiles = ref({ hero: null, editorial: null })
const siteImagePreviews = ref({ hero: '', editorial: '' })
const checkoutOpen = ref(false), recipient = ref({ recipientName: '', recipientPhone: '', shippingAddress: '' }), detailOrder = ref(null), paymentSecondsLeft = ref(0)
const shippingOrder = ref(null), shippingForm = ref({ deliveryType: 'PHYSICAL', trackingNumber: '', virtualCode: '' }), shippingError = ref(''), shippingBusy = ref(false), receiptBusy = ref([])
let orderRefreshTimer = null
let orderRefreshBusy = false
let paymentCountdownTimer = null
let paymentCountdownGeneration = 0
const paymentClock = createPaymentClock()
const isAdmin = computed(() => user.value?.role === 'ADMIN')
const walletOpen = ref(false), walletBalance = ref(null), paymentBusy = ref(false)
const profileForm = ref({ username: '', email: '', currentPassword: '' })
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const profileBusy = ref(false), passwordBusy = ref(false), profileError = ref(''), passwordError = ref('')
const cardKeyProduct = ref(null), revealedCards = ref({})
const theme = ref(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light')
function toggleTheme() {
  applyTheme(theme.value === 'dark' ? 'light' : 'dark')
  try { localStorage.setItem('novamart_theme', theme.value) } catch {}
}
function applyTheme(value) {
  theme.value = value === 'dark' ? 'dark' : 'light'
  document.documentElement.dataset.theme = theme.value
  document.documentElement.style.colorScheme = theme.value
  const browserColor = document.querySelector('meta[name="theme-color"]')
  if (browserColor) browserColor.content = theme.value === 'dark' ? '#1C1917' : '#F7F4EF'
}
function syncTheme(event) {
  if (event.key === 'novamart_theme' || event.key === null) applyTheme(event.newValue)
}
const walletEnough = computed(() => walletBalance.value !== null && Math.round(Number(walletBalance.value) * 100) >= Math.round(Number(detailOrder.value?.order?.total_amount || 0) * 100))
async function loadWallet() { if (!user.value) return; try { walletBalance.value = (await api.get('/wallet')).data.balance } catch (error) { walletBalance.value = null; flash(errorText(error)) } }
async function openWallet() { if (!user.value) return openAuth(); walletOpen.value = true; await loadWallet() }
function walletUpdated(balance) { walletBalance.value = balance }
async function refundOrder(order) {
  const remark = window.prompt(`确认将订单 #${order.id} 全额退回用户钱包？请填写退款原因：`)
  if (!remark?.trim()) return
  try { const result = (await api.post(`/admin/wallet/orders/${order.id}/refund`, { remark: remark.trim() })).data; flash(result.message); await loadAdminOrders(); await loadWallet() } catch (error) { flash(errorText(error)) }
}
const cartCount = computed(() => cart.value.reduce((sum, item) => sum + item.quantity, 0))
const cartTotal = computed(() => cart.value.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0))
const formatMoney = value => `￥${Number(value || 0).toFixed(2)}`
const currentMonthDay = computed(() => {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${month} / ${day}`
})
const statusText = value => ({ PENDING_PAYMENT: '待付款', PAID: '已付款', PROCESSING: '处理中', SHIPPED: '已发货', COMPLETED: '已完成', CANCELLED: '已取消', ON_SALE: '在售', OFF_SALE: '已下架' })[value] || value
const flash = message => { toast.value = message; window.setTimeout(() => { toast.value = '' }, 2800) }
const errorText = error => {
  const data = error?.response?.data
  if (typeof data === 'string' && data.trim()) return data
  if (data?.detail) return data.detail
  if (data?.message) return data.message
  if (error?.code === 'ERR_NETWORK') return '无法连接服务器，请确认 Spring Boot 后端已启动（localhost:8081）。'
  if (error?.response?.status === 404 && (error?.config?.url?.includes('/admin/product-images') || error?.config?.url?.includes('/admin/site-images') || error?.config?.url?.includes('/admin/site-settings'))) return '图片接口未加载，请重启 OnlineShopApplication 后再试。'
  if (error?.response?.status === 400) return '请求参数不正确，请检查输入内容。'
  if (error?.response?.status === 401) return '登录信息不正确，请检查用户名和密码。'
  if (error?.response?.status === 403) return '没有权限执行此操作。'
  if (error?.response?.status === 404) return '请求的数据不存在。'
  if (error?.response?.status === 409) return '数据已存在，请更换后重试。'
  if (error?.response?.status === 413) return '提交内容过大，请压缩后重试。'
  if (error?.response?.status === 500) return '服务器或数据库处理失败，请查看后端控制台日志。'
  if (error?.response?.status === 503) return '服务暂时不可用，请检查数据库连接。'
  return '操作失败，请稍后重试'
}
function blankProduct() { return { id: null, categoryId: null, name: '', description: '', price: 0, stock: 0, imageUrl: '', status: 'ON_SALE', isFeatured: false, deliveryType: 'PHYSICAL' } }
function cardsVisible(order) { return Boolean(revealedCards.value[order.id]) }
function toggleCards(order) { revealedCards.value = { ...revealedCards.value, [order.id]: !cardsVisible(order) } }
async function copyCards(order) { try { await navigator.clipboard.writeText(order.delivered_cards); flash('卡密已复制，快去使用吧～') } catch { flash('浏览器未授权剪贴板，请手动选择卡密复制') } }
function cover(product) { return product.image_url || product.imageUrl || '/product-placeholder.svg' }
function imageFallback(event) { const image = event?.target; if (!image || image.dataset.fallbackApplied) return; image.dataset.fallbackApplied = 'true'; image.src = '/product-placeholder.svg' }
const defaultSiteImages = {
  hero: 'https://images.unsplash.com/photo-1503602642458-232111445657?auto=format&fit=crop&w=900&q=85',
  editorial: 'https://images.unsplash.com/photo-1503602642458-232111445657?auto=format&fit=crop&w=900&q=85'
}
function siteImage(type) { return siteSettings.value[type === 'hero' ? 'heroImageUrl' : 'editorialImageUrl'] || defaultSiteImages[type] }
function cartItemFor(product) { return cart.value.find(item => Number(item.product_id) === Number(product.id)) }
function cartQuantity(product) { return Number(cartItemFor(product)?.quantity || 0) }
function cartBusy(product) { return cartBusyProducts.value.includes(Number(product.id)) }
function setView(next) { mobileMenu.value = false; if (['cart', 'orders', 'order-detail', 'profile'].includes(next) && !user.value) return openAuth(); if (next.startsWith('admin') && !isAdmin.value) return flash('需要管理员权限'); if (next !== 'order-detail') stopPaymentCountdown(); view.value = next; if (next === 'store') { loadProducts(); loadFeaturedProducts() } if (next === 'cart') loadCart(); if (next === 'orders') loadOrders(); if (next === 'profile') loadProfile(); if (next === 'admin-products') loadAdminProducts(); if (next === 'admin-orders') loadAdminOrders(); if (next === 'admin-stats') loadStats(); if (next === 'admin-site') loadAdminSiteSettings() }
async function loadProfile() {
  profileError.value = ''
  try {
    const result = (await api.get('/account/profile')).data
    profileForm.value = { username: result.username || '', email: result.email || '', currentPassword: '' }
    user.value = { ...user.value, username: result.username, email: result.email, role: result.role }
    localStorage.setItem('shop_user', JSON.stringify(user.value))
  } catch (error) { profileError.value = errorText(error) }
}
function sanitizeProfileInput(field, event) {
  if (event?.isComposing || event?.inputType === 'insertCompositionText') return
  const raw = event?.target?.value ?? profileForm.value[field] ?? ''
  const sanitized = sanitizeAuthValue(field, raw)
  if (event?.target && event.target.value !== sanitized) event.target.value = sanitized
  profileForm.value[field] = sanitized
}
async function saveProfile() {
  if (profileBusy.value) return
  profileError.value = ''
  if (!usernameRule.test(profileForm.value.username)) { profileError.value = '用户名需为 3-30 位英文字母、数字或下划线'; return }
  if (!emailRule.test(profileForm.value.email)) { profileError.value = '请输入有效的标准英文邮箱'; return }
  if (!profileForm.value.currentPassword) { profileError.value = '请输入当前密码以确认身份'; return }
  profileBusy.value = true
  try {
    const result = (await api.put('/account/profile', profileForm.value)).data
    user.value = { ...user.value, username: result.username, email: result.email }
    localStorage.setItem('shop_user', JSON.stringify(user.value))
    profileForm.value.currentPassword = ''
    flash('个人资料已更新')
  } catch (error) { profileError.value = errorText(error) }
  finally { profileBusy.value = false }
}
async function savePassword() {
  if (passwordBusy.value) return
  passwordError.value = ''
  if (!passwordForm.value.currentPassword) { passwordError.value = '请输入当前密码'; return }
  if (!passwordRule.test(passwordForm.value.newPassword)) { passwordError.value = '新密码需为 6-64 位英文、数字或符号'; return }
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) { passwordError.value = '两次输入的新密码不一致'; return }
  passwordBusy.value = true
  try {
    const result = (await api.put('/account/password', { currentPassword: passwordForm.value.currentPassword, newPassword: passwordForm.value.newPassword })).data
    const username = user.value?.username || ''
    localStorage.removeItem('shop_token')
    localStorage.removeItem('shop_user')
    user.value = null
    cart.value = []
    walletBalance.value = null
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    authMode.value = 'login'
    authForm.value = { username, email: '', password: '', newPassword: '', confirmPassword: '', resetToken: '' }
    view.value = 'auth'
    flash(result.message)
  } catch (error) { passwordError.value = errorText(error) }
  finally { passwordBusy.value = false }
}
function selectCategory(id) { categoryId.value = Number(id); setView('store') }
function clearCategory() { categoryId.value = null; setView('store') }
function scrollToProducts() { document.querySelector('.catalog-title')?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
async function exploreFeatured() {
  if (view.value !== 'store') { setView('store'); await nextTick() }
  featuredSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function loadFeaturedProducts() {
  try { featuredProducts.value = (await api.get('/featured-products')).data }
  catch (error) { featuredProducts.value = []; console.warn('本周精选加载失败', error) }
}
async function toggleFeatured(product) {
  if (featuredBusy.value.includes(product.id)) return
  featuredBusy.value.push(product.id)
  try {
    const result = (await api.put(`/admin/products/${product.id}/featured`, { isFeatured: !product.is_featured })).data
    product.is_featured = result.isFeatured
    await loadFeaturedProducts()
    flash(result.message)
  } catch (error) { flash(errorText(error)) }
  finally { featuredBusy.value = featuredBusy.value.filter(id => id !== product.id) }
}
async function loadProducts() { loading.value = true; dataError.value = ''; try { products.value = (await api.get('/products', { params: { q: search.value, categoryId: categoryId.value || undefined } })).data } catch (error) { products.value = []; dataError.value = errorText(error); flash(dataError.value) } finally { loading.value = false } }
async function searchProducts() { stopPaymentCountdown(); view.value = 'store'; categoryId.value = null; mobileMenu.value = false; await loadProducts(); await nextTick(); document.querySelector('.catalog-title')?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
async function loadCategories() { try { categories.value = (await api.get('/categories')).data; if (categoryId.value !== null && !categories.value.some(c => Number(c.id) === categoryId.value)) { categoryId.value = null; await loadProducts() } } catch (error) { categories.value = []; flash(errorText(error)) } }
async function loadAdminCategories() { adminCategories.value = (await api.get('/admin/categories')).data }
async function categoriesUpdated() { await Promise.all([loadCategories(), loadProducts(), loadFeaturedProducts(), loadAdminProducts()]) }
function openAuth(mode = 'login') { authMode.value = mode; authErrors.value = {}; view.value = 'auth' }
function setAuthMode(mode) { authMode.value = mode; authErrors.value = {} }
const usernameRule = /^[A-Za-z0-9_]{3,30}$/
const emailRule = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/
const passwordRule = /^[\x21-\x7E]{6,64}$/
function clearAuthError(field) { if (authErrors.value[field]) authErrors.value = { ...authErrors.value, [field]: '' } }
function switchAuthMode() { authMode.value = authMode.value === 'login' ? 'register' : 'login'; authErrors.value = {} }
function authFieldError(field) {
  const value = authForm.value[field] || ''
  if (!value) {
    if (field === 'username' && ['login', 'register'].includes(authMode.value)) return '请输入用户名'
    if (field === 'email' && ['register', 'forgot'].includes(authMode.value)) return '请输入邮箱'
    if (field === 'password' && ['login', 'register'].includes(authMode.value)) return '请输入密码'
    if (field === 'newPassword' && authMode.value === 'reset') return '请输入新密码'
    if (field === 'confirmPassword' && authMode.value === 'reset') return '请再次输入新密码'
    return ''
  }
  if (field === 'username' && !usernameRule.test(value)) return '用户名格式不正确：3-30 位，仅能使用英文字母、数字和下划线'
  if (field === 'email' && ['register', 'forgot'].includes(authMode.value) && !emailRule.test(value)) return '请输入有效邮箱，例如 name@example.com'
  if (['password', 'newPassword', 'confirmPassword'].includes(field) && !passwordRule.test(value)) return '密码格式不正确：6-64 位，仅能使用英文、数字和符号'
  if (field === 'confirmPassword' && value !== authForm.value.newPassword) return '两次输入的新密码不一致'
  return ''
}
function sanitizeAuthValue(field, value) {
  if (field === 'username') return value.replace(/[^A-Za-z0-9_]/g, '')
  if (field === 'email') return value.replace(/[^A-Za-z0-9.!#$%&'*+\/=?^_\x60{|}~@-]/g, '')
  return value.replace(/[^\x21-\x7E]/g, '')
}
function handleAuthInput(field, event) {
  if (event?.isComposing || event?.inputType === 'insertCompositionText') return
  const raw = event?.target?.value ?? authForm.value[field] ?? ''
  const sanitized = sanitizeAuthValue(field, raw)
  if (event?.target && event.target.value !== sanitized) event.target.value = sanitized
  authForm.value[field] = sanitized
  const error = authFieldError(field)
  authErrors.value = { ...authErrors.value, [field]: error }
}
function validateAuth() {
  const errors = {}
  if (['login', 'register'].includes(authMode.value)) {
    if (!authForm.value.username) errors.username = '请输入用户名'
    else if (!usernameRule.test(authForm.value.username)) errors.username = '用户名格式不正确：3-30 位，仅支持英文字母、数字和下划线'
  }
  if (['register', 'forgot'].includes(authMode.value)) {
    if (!authForm.value.email) errors.email = '请输入邮箱'
    else if (!emailRule.test(authForm.value.email)) errors.email = '请输入有效邮箱，例如 name@example.com'
  }
  if (['login', 'register'].includes(authMode.value)) {
    if (!authForm.value.password) errors.password = '请输入密码'
    else if (!passwordRule.test(authForm.value.password)) errors.password = '密码格式不正确：6-64 位，仅支持英文、数字和符号'
  }
  if (authMode.value === 'reset') {
    if (!authForm.value.resetToken) errors.resetToken = '重设链接无效，请重新申请'
    if (!authForm.value.newPassword) errors.newPassword = '请输入新密码'
    else if (!passwordRule.test(authForm.value.newPassword)) errors.newPassword = '新密码需为 6-64 位英文、数字或符号'
    if (!authForm.value.confirmPassword) errors.confirmPassword = '请再次输入新密码'
    else if (authForm.value.newPassword !== authForm.value.confirmPassword) errors.confirmPassword = '两次输入的新密码不一致'
  }
  authErrors.value = errors
  if (Object.keys(errors).length) window.alert(`请检查${({ login: '登录', register: '注册', forgot: '找回密码', reset: '重设密码' })[authMode.value]}信息：\n${Object.values(errors).map(message => `· ${message}`).join('\n')}`)
  return Object.keys(errors).length === 0
}
async function submitAuth() {
  if (!validateAuth()) return
  try {
    if (authMode.value === 'forgot') {
      const result = (await api.post('/auth/forgot-password', { email: authForm.value.email })).data
      flash(result.message || '如果邮箱已注册，重设链接会发送到你的邮箱')
      authMode.value = 'login'
      authForm.value = { ...authForm.value, password: '', newPassword: '', confirmPassword: '', resetToken: '' }
      authErrors.value = {}
      return
    }
    if (authMode.value === 'reset') {
      const result = (await api.post('/auth/reset-password', { token: authForm.value.resetToken, newPassword: authForm.value.newPassword })).data
      authMode.value = 'login'
      authForm.value = { username: '', email: '', password: '', newPassword: '', confirmPassword: '', resetToken: '' }
      authErrors.value = {}
      flash(result.message || '密码已重设，请使用新密码登录')
      return
    }
    if (authMode.value === 'register') {
      await api.post('/auth/register', { username: authForm.value.username, email: authForm.value.email, password: authForm.value.password })
      authMode.value = 'login'
      authForm.value.password = ''
      authErrors.value = {}
      flash('注册成功，请登录')
      return
    }
    const result = (await api.post('/auth/login', { username: authForm.value.username, password: authForm.value.password })).data
    localStorage.setItem('shop_token', result.token)
    localStorage.setItem('shop_user', JSON.stringify(result))
    user.value = result
    await loadCart()
    await loadWallet()
    view.value = 'store'
    authForm.value.password = ''
    flash('欢迎回来，' + result.username)
  } catch (error) { flash(errorText(error)) }
}
async function logout() { try { await api.post('/auth/logout') } catch { /* Local logout remains available if the network is offline. */ } stopPaymentCountdown(); localStorage.removeItem('shop_token'); localStorage.removeItem('shop_user'); walletOpen.value = false; walletBalance.value = null; user.value = null; cart.value = []; view.value = 'store'; flash('已退出登录') }
async function loadCart() { try { cart.value = (await api.get('/cart')).data } catch (error) { flash(errorText(error)) } }
async function addToCart(product) { if (!user.value) return openAuth(); const productId = Number(product.id); if (cartBusyProducts.value.includes(productId)) return; cartBusyProducts.value = [...cartBusyProducts.value, productId]; try { await api.post('/cart/items', { productId, quantity: 1 }); await loadCart(); flash('已加入购物车') } catch (error) { flash(errorText(error)) } finally { cartBusyProducts.value = cartBusyProducts.value.filter(id => id !== productId) } }
async function changeProductQuantity(product, delta) { if (!user.value) return openAuth(); const item = cartItemFor(product); if (!item) return delta > 0 ? addToCart(product) : undefined; const productId = Number(product.id); if (cartBusyProducts.value.includes(productId)) return; const quantity = Number(item.quantity) + delta; if (quantity > Number(product.stock)) return flash('已达到库存上限'); cartBusyProducts.value = [...cartBusyProducts.value, productId]; try { if (quantity < 1) await api.delete(`/cart/items/${item.id}`); else await api.put(`/cart/items/${item.id}`, { quantity }); await loadCart() } catch (error) { flash(errorText(error)) } finally { cartBusyProducts.value = cartBusyProducts.value.filter(id => id !== productId) } }
async function updateQuantity(item, delta) { const quantity = item.quantity + delta; if (quantity < 1) return removeCart(item.id); try { await api.put(`/cart/items/${item.id}`, { quantity }); await loadCart() } catch (error) { flash(errorText(error)) } }
async function removeCart(id) { try { await api.delete(`/cart/items/${id}`); await loadCart(); flash('商品已移除') } catch (error) { flash(errorText(error)) } }
async function placeOrder() {
  const request = { recipientName: recipient.value.recipientName.trim(), recipientPhone: recipient.value.recipientPhone.trim(), shippingAddress: recipient.value.shippingAddress.trim() }
  if (!request.recipientName || !request.recipientPhone || !request.shippingAddress) return flash('请完整填写收货人、手机号和收货地址')
  if (!/^1[3-9]\d{9}$/.test(request.recipientPhone)) return flash('请输入有效的 11 位中国大陆手机号')
  try {
    const result = (await api.post('/orders', request)).data
    checkoutOpen.value = false
    recipient.value = { recipientName: '', recipientPhone: '', shippingAddress: '' }
    await loadCart()
    await loadOrders()
    await openOrder({ id: result.orderId })
    flash(`订单 #${result.orderId} 已提交，请在 15 分钟内完成付款`)
  } catch (error) { flash(errorText(error)) }
}
async function loadOrders() { try { orders.value = (await api.get('/orders')).data } catch (error) { flash(errorText(error)) } }
function stopPaymentCountdown() { paymentCountdownGeneration++; if (paymentCountdownTimer) window.clearInterval(paymentCountdownTimer); paymentCountdownTimer = null }
function startPaymentCountdown(order) {
  stopPaymentCountdown()
  paymentClock.sync(order)
  paymentSecondsLeft.value = paymentClock.secondsLeft()
  if (order.status !== 'PENDING_PAYMENT') return
  const generation = paymentCountdownGeneration
  let checking = false
  let retryAt = 0
  const tick = async () => {
    if (generation !== paymentCountdownGeneration) return
    paymentSecondsLeft.value = paymentClock.secondsLeft()
    if (paymentSecondsLeft.value === 0) {
      if (checking || performance.now() < retryAt) return
      checking = true
      retryAt = performance.now() + 2000
      try {
        const detail = (await api.get(`/orders/${order.id}`, { headers: { 'Cache-Control': 'no-cache' } })).data
        if (generation !== paymentCountdownGeneration) return
        detailOrder.value = detail
        if (detail.order.status !== 'PENDING_PAYMENT') {
          stopPaymentCountdown()
          if (detail.order.status === 'CANCELLED') flash('付款时间已过，订单已自动取消')
          await loadOrders()
        }
      } catch (error) { if (generation === paymentCountdownGeneration) flash(errorText(error)) }
      finally { checking = false }
    }
  }
  paymentCountdownTimer = window.setInterval(tick, 250)
  tick()
}
function formatCountdown(seconds) { const value = Math.max(0, Number(seconds) || 0); return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}` }
async function openOrder(order) {
  try {
    detailOrder.value = (await api.get(`/orders/${order.id}`)).data
    await loadWallet()
    view.value = 'order-detail'
    startPaymentCountdown(detailOrder.value.order)
  } catch (error) { flash(errorText(error)) }
}
async function payOrder() {
  const id = detailOrder.value?.order?.id
  if (!id || paymentBusy.value) return
  paymentBusy.value = true
  try {
    const result = (await api.post(`/orders/${id}/pay`)).data
    walletBalance.value = result.balance
    detailOrder.value = (await api.get(`/orders/${id}`)).data
    await loadOrders()
    startPaymentCountdown(detailOrder.value.order)
    if (detailOrder.value.order.delivered_cards) {
      revealedCards.value = { ...revealedCards.value, [id]: true }
      setView('orders')
      await nextTick()
      document.querySelector('.order-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
    flash(result.message || '付款成功')
  } catch (error) {
    flash(errorText(error))
    try { detailOrder.value = (await api.get(`/orders/${id}`)).data; await loadOrders(); startPaymentCountdown(detailOrder.value.order) } catch { /* the existing payment error is already shown */ }
  } finally { paymentBusy.value = false }
}
async function loadAdminProducts() { try { adminProducts.value = (await api.get('/admin/products')).data } catch (error) { flash(errorText(error)) } }
async function loadSiteSettings() { try { siteSettings.value = (await api.get('/site-settings')).data } catch (error) { siteSettings.value = { heroImageUrl: '', editorialImageUrl: '' } } }
async function loadAdminSiteSettings() { try { const data = (await api.get('/admin/site-settings')).data; siteForm.value = { heroImageUrl: data.heroImageUrl || '', editorialImageUrl: data.editorialImageUrl || '' }; siteImagePreviews.value = { hero: data.heroImageUrl || '', editorial: data.editorialImageUrl || '' } } catch (error) { flash(errorText(error)) } }
function chooseSiteImage(type, event) { const file = event.target.files?.[0]; if (!file) return; siteImageFiles.value = { ...siteImageFiles.value, [type]: file }; siteImagePreviews.value = { ...siteImagePreviews.value, [type]: URL.createObjectURL(file) } }
async function saveSiteSettings() { try { const data = { ...siteForm.value }; for (const type of ['hero', 'editorial']) { const file = siteImageFiles.value[type]; if (!file) continue; const form = new FormData(); form.append('file', file); const result = (await api.post('/admin/site-images', form, { headers: { 'Content-Type': 'multipart/form-data' } })).data; if (type === 'hero') data.heroImageUrl = result.url; else data.editorialImageUrl = result.url } await api.put('/admin/site-settings', data); siteImageFiles.value = { hero: null, editorial: null }; await loadSiteSettings(); await loadAdminSiteSettings(); flash('首页图片已保存') } catch (error) { flash(errorText(error)) } }
async function newProduct() { try { await loadAdminCategories(); productForm.value = blankProduct(); productImageFile.value = null; productImagePreview.value = ''; editingProduct.value = false; view.value = 'admin-editor' } catch (error) { flash(errorText(error)) } }
async function editProduct(product) { try { await loadAdminCategories(); productForm.value = { id: product.id, categoryId: product.category_id == null ? null : Number(product.category_id), name: product.name, description: product.description || '', price: Number(product.price), stock: product.stock, imageUrl: product.image_url || '', status: product.status, isFeatured: Boolean(product.is_featured), deliveryType: product.delivery_type || 'PHYSICAL' }; productImageFile.value = null; productImagePreview.value = product.image_url || ''; editingProduct.value = true; view.value = 'admin-editor' } catch (error) { flash(errorText(error)) } }
function chooseProductImage(event) { const file = event.target.files?.[0]; if (!file) return; productImageFile.value = file; productImagePreview.value = URL.createObjectURL(file) }
async function saveProduct() { try { const data = { ...productForm.value }; if (productImageFile.value) { const form = new FormData(); form.append('file', productImageFile.value); data.imageUrl = (await api.post('/admin/product-images', form, { headers: { 'Content-Type': 'multipart/form-data' } })).data.url } if (editingProduct.value) await api.put(`/admin/products/${data.id}`, data); else await api.post('/admin/products', data); flash(editingProduct.value ? '商品已保存' : '商品已新增'); await Promise.all([loadAdminProducts(), loadCategories(), loadProducts(), loadFeaturedProducts()]); view.value = 'admin-products' } catch (error) { flash(errorText(error)) } }
async function unlistProduct(product) { if (!window.confirm(`确认下架“${product.name}”？`)) return; try { await api.delete(`/admin/products/${product.id}`); await Promise.all([loadAdminProducts(), loadCategories(), loadProducts(), loadFeaturedProducts()]); flash('商品已下架') } catch (error) { flash(errorText(error)) } }
async function loadAdminOrders() { try { adminOrders.value = (await api.get('/admin/orders')).data } catch (error) { flash(errorText(error)) } }
async function changeOrderStatus(order, status) { try { await api.put(`/admin/orders/${order.id}/status`, { status }); await loadAdminOrders(); flash('订单状态已更新') } catch (error) { flash(errorText(error)) } }
function openShipping(order) {
  shippingOrder.value = order
  shippingForm.value = { deliveryType: 'PHYSICAL', trackingNumber: '', virtualCode: '' }
  shippingError.value = ''
}
async function submitShipping() {
  if (shippingBusy.value) return
  const data = { ...shippingForm.value, trackingNumber: shippingForm.value.trackingNumber.trim(), virtualCode: shippingForm.value.virtualCode.trim() }
  if (data.deliveryType === 'PHYSICAL' && !/^[A-Za-z0-9-]{1,100}$/.test(data.trackingNumber)) { shippingError.value = '请输入快递单号，支持英文字母、数字和短横线，最多 100 位'; return }
  if (data.deliveryType === 'VIRTUAL' && (!data.virtualCode || data.virtualCode.length > 2000)) { shippingError.value = '请输入虚拟卡密，最多 2000 个字符'; return }
  shippingBusy.value = true; shippingError.value = ''
  try {
    const result = (await api.post(`/admin/orders/${shippingOrder.value.id}/ship`, data)).data
    shippingOrder.value = null
    await loadAdminOrders()
    flash(result.message)
  } catch (error) { shippingError.value = errorText(error) }
  finally { shippingBusy.value = false }
}
async function confirmReceipt(order) {
  if (receiptBusy.value.includes(order.id)) return
  if (!window.confirm('确认已收到商品或虚拟卡密？确认后订单将变为已完成。')) return
  receiptBusy.value.push(order.id)
  try {
    const result = (await api.post(`/orders/${order.id}/confirm-receipt`)).data
    await loadOrders()
    if (view.value === 'order-detail' && detailOrder.value?.order.id === order.id) await openOrder(order)
    flash(result.message)
  } catch (error) { flash(errorText(error)); await refreshVisibleOrders() }
  finally { receiptBusy.value = receiptBusy.value.filter(id => id !== order.id) }
}
async function refreshVisibleOrders() {
  if (!user.value || document.hidden || orderRefreshBusy) return
  const currentView = view.value
  if (!['orders', 'admin-orders', 'order-detail'].includes(currentView)) return
  orderRefreshBusy = true
  try {
    if (currentView === 'orders') {
      const data = (await api.get('/orders')).data
      if (view.value === currentView && user.value) orders.value = data
    } else if (currentView === 'admin-orders') {
      const data = (await api.get('/admin/orders')).data
      if (view.value === currentView && isAdmin.value) adminOrders.value = data
    } else {
      const id = detailOrder.value?.order.id
      if (!id) return
      const data = (await api.get(`/orders/${id}`)).data
      if (view.value !== currentView || detailOrder.value?.order.id !== id || !user.value) return
      const previousStatus = detailOrder.value.order.status
      detailOrder.value = data
      if (previousStatus !== data.order.status) startPaymentCountdown(data.order)
    }
  } catch { /* Keep the current order visible; manual refresh reports failures. */ }
  finally { orderRefreshBusy = false }
}
async function loadStats() {
  statsBusy.value = true
  try {
    const params = { period: statsPeriod.value }
    const [overviewResult, salesResult, productsResult] = await Promise.all([
      api.get('/admin/statistics/overview'),
      api.get('/admin/statistics/sales', { params }),
      api.get('/admin/statistics/top-products', { params })
    ])
    overview.value = overviewResult.data
    sales.value = salesResult.data
    topProducts.value = productsResult.data
  } catch (error) { flash(errorText(error)) }
  finally { statsBusy.value = false }
}
async function selectStatsPeriod(period) { if (statsPeriod.value === period || statsBusy.value) return; statsPeriod.value = period; await loadStats() }
function statsPeriodLabel(value) { return statsPeriod.value === 'day' ? value : statsPeriod.value === 'week' ? `周起 ${value}` : value.slice(0, 7) }
function handleAuthExpired() {
  stopPaymentCountdown()
  walletOpen.value = false
  walletBalance.value = null
  user.value = null
  cart.value = []
  view.value = 'auth'
  authMode.value = 'login'
  flash('登录已失效，请重新登录')
}
onMounted(async () => {
  window.addEventListener('storage', syncTheme)
  window.addEventListener('shop:auth-expired', handleAuthExpired)
  document.addEventListener('visibilitychange', refreshVisibleOrders)
  orderRefreshTimer = window.setInterval(refreshVisibleOrders, 10000)
  const resetToken = new URLSearchParams(window.location.search).get('resetToken')
  if (resetToken) {
    authForm.value.resetToken = resetToken
    authMode.value = 'reset'
    view.value = 'auth'
    window.history.replaceState({}, '', window.location.pathname)
  }
  await Promise.all([loadCategories(), loadProducts(), loadSiteSettings(), loadFeaturedProducts()])
  if (user.value) { await loadCart(); await loadWallet() }
})
onUnmounted(() => {
  window.removeEventListener('storage', syncTheme)
  stopPaymentCountdown()
  window.clearInterval(orderRefreshTimer)
  document.removeEventListener('visibilitychange', refreshVisibleOrders)
  window.removeEventListener('shop:auth-expired', handleAuthExpired)
})
</script>

<template>
  <div class="app-shell">
    <div class="announcement"><span>NovaMart / 以设计挑选好物</span><span>全场精选 · 简单购物 · 安心交付</span></div>
    <header class="site-header">
      <button class="brand" aria-label="NovaMart 首页" @click="setView('store')"><span class="brand-symbol">N</span><span>novamart<span class="brand-dot">.</span></span></button>
      <div class="global-search">
        <svg class="nav-icon search-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 4.5 4.5"/></svg>
        <input v-model="search" aria-label="搜索商品" placeholder="搜索你正在寻找的好物" @keyup.enter="searchProducts">
        <button @click="searchProducts">搜索</button>
      </div>
      <nav class="header-actions" aria-label="账户与购物">
        <button class="header-link" type="button" :aria-label="user ? '打开个人资料' : '登录或注册账户'" @click="user ? setView('profile') : openAuth()"><svg class="account-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9.5"/><circle cx="12" cy="8.5" r="3"/><path d="M5.4 18.4c.8-2.8 3.2-4.4 6.6-4.4s5.8 1.6 6.6 4.4"/></svg><span><small>{{ user ? `你好，${user.username}` : '欢迎光临' }}</small><b>{{ user ? '个人资料' : '登录 / 注册' }}</b></span></button>
        <button class="header-link orders-header-link" type="button" :class="{ active: view === 'orders' || view === 'order-detail' }" aria-label="打开我的订单" @click="setView('orders')"><svg class="orders-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 3.5h14v17H5z"/><path d="M8 8h8M8 12h8m-8 4h5"/></svg><b>我的订单</b></button>
        <button v-if="user" class="wallet-pill" type="button" aria-label="打开我的钱包" title="查看余额与收支明细" @click="openWallet"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8V5a2 2 0 0 1 2-2h13v3"/><rect x="3" y="6" width="18" height="15" rx="2"/><path d="M21 11h-6v5h6"/><path d="M17.5 13.5h.01"/></svg><span class="wallet-pill-copy"><span class="wallet-pill-label">余额</span><strong><span v-if="walletBalance !== null" class="wallet-currency">¥</span>{{ walletBalance === null ? '—' : Number(walletBalance).toFixed(2) }}</strong></span></button>
        <button class="bag-link" aria-label="打开购物袋" @click="setView('cart')"><svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7.5h14l1 13H4l1-13Z"/><path d="M8.5 8V6a3.5 3.5 0 0 1 7 0v2"/></svg><b>购物袋</b><i v-if="cartCount">{{ cartCount }}</i></button>
        <button class="theme-toggle" type="button" :aria-pressed="theme === 'dark'" :aria-label="theme === 'dark' ? '切换到日间模式' : '切换到暖夜模式'" :title="theme === 'dark' ? '日间模式' : '暖夜模式'" @click="toggleTheme"><svg class="theme-icon theme-sun" :class="{ visible: theme === 'light' }" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42"/></svg><svg class="theme-icon theme-moon" :class="{ visible: theme === 'dark' }" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.5 15.5A8.5 8.5 0 0 1 8.5 3.5 8.5 8.5 0 1 0 20.5 15.5Z"/><path d="m16.5 4 .5 1.5 1.5.5-1.5.5-.5 1.5-.5-1.5-1.5-.5 1.5-.5.5-1.5Z"/></svg></button>
        <button class="mobile-toggle" :aria-expanded="mobileMenu" aria-label="展开或收起导航" @click="mobileMenu = !mobileMenu"><svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg></button>
      </nav>
    </header>
    <nav class="main-nav" :class="{ open: mobileMenu }"><button :class="{ active: view === 'store' && categoryId === null }" @click="clearCategory">所有商品</button><button v-for="c in categories" :key="c.id" :class="{ active: view === 'store' && categoryId === Number(c.id) }" @click="selectCategory(c.id)">{{ c.name }}</button><span class="nav-spacer"></span><button class="compact-account-link" :class="{ active: view === 'profile' || view === 'auth' }" @click="user ? setView('profile') : openAuth()">{{ user ? '个人资料' : '登录 / 注册' }}</button><button class="compact-account-link" :class="{ active: view === 'orders' || view === 'order-detail' }" @click="setView('orders')">我的订单</button><template v-if="isAdmin"><button class="admin-nav-link" :class="{ active: ['admin-products', 'admin-editor', 'admin-categories'].includes(view) }" @click="setView('admin-products')">商品管理</button><button class="admin-nav-link" :class="{ active: view === 'admin-orders' }" @click="setView('admin-orders')">订单管理</button><button class="admin-nav-link" :class="{ active: view === 'admin-support' }" @click="setView('admin-support')">客服与工单</button><button class="admin-nav-link" :class="{ active: view === 'admin-wallet' }" @click="setView('admin-wallet')">钱包 / 储值管理</button><button class="admin-nav-link" :class="{ active: view === 'admin-stats' }" @click="setView('admin-stats')">经营数据</button><button class="admin-nav-link" :class="{ active: view === 'admin-site' }" @click="setView('admin-site')">首页设置</button></template><button v-if="user" class="nav-logout" @click="logout">退出登录</button></nav>
    <main>
      <section v-if="view === 'store'" class="store-page">
        <section class="hero" aria-label="NovaMart 精选介绍">
          <article class="hero-copy"><span class="overline">THE EDIT / 2026</span><h1>让日常，<br><em>更值得。</em></h1><p>我们挑选经得起时间的设计与质感，<br>为生活留下一点恰到好处的美好。</p><button class="hero-button" @click="exploreFeatured">探索精选 <span>→</span></button></article>
          <div class="hero-art"><div class="hero-orbit"></div><img :src="siteImage('hero')" alt="精选生活好物" @error="imageFallback"><div class="hero-note"><span>精选</span><strong>{{ currentMonthDay }}</strong></div></div>
        </section>
        <section id="weekly-featured" ref="featuredSection" class="weekly-featured" :class="{ 'is-empty': !featuredProducts.length }" aria-labelledby="featured-heading">
          <div class="collection-intro"><div><span class="overline">CURATED FOR YOU</span><h2 id="featured-heading">本周精选</h2></div><p>不追逐潮流，只留下真正值得拥有的东西。</p></div>
          <div v-if="featuredProducts.length" class="product-grid"><ProductCard v-for="product in featuredProducts" :key="product.id" :product="product" :quantity="cartQuantity(product)" :busy="cartBusy(product)" @open="activeProduct = $event" @add="addToCart" @change="changeProductQuantity" /></div>
        </section>
        <div class="catalog-title"><div><span class="overline">DISCOVER</span><h2>{{ search.trim() ? `搜索：“${search.trim()}”` : categoryId ? '分类精选' : '全部好物' }}</h2></div><span>{{ products.length }} 件商品</span></div>
        <div v-if="loading" class="empty-state">正在加载精选商品...</div>
        <div v-else-if="dataError" class="empty-state error-state"><strong>商品暂时加载失败</strong><span>{{ dataError }}</span><button class="secondary" @click="loadProducts">重新加载</button></div>
        <div v-else-if="!products.length" class="empty-state">没有匹配的商品，换个关键词试试。</div>
        <div v-else class="product-grid"><ProductCard v-for="product in products" :key="product.id" :product="product" :quantity="cartQuantity(product)" :busy="cartBusy(product)" @open="activeProduct = $event" @add="addToCart" @change="changeProductQuantity" /></div>
         <section v-if="products.length" class="editorial" aria-label="NovaMart 选品理念"><figure class="editorial-image"><img :src="siteImage('editorial')" alt="编辑精选" @error="imageFallback"></figure><article class="editorial-copy"><span class="overline">THE NOVAMART NOTE</span><h2>少一点选择，<br>多一点喜欢。</h2><p>我们相信，好的商品不需要喧哗。每一件上架的物品，都经过认真筛选，只为让你更轻松地找到适合自己的那一个。</p><button class="text-link" @click="scrollToProducts">了解我们的选品理念 <span>→</span></button></article></section>
      </section>
<section v-else-if="view === 'auth'" class="auth-page"><div class="auth-visual"><span class="overline">A QUIET PLACE FOR GOOD THINGS</span><h1>欢迎来到<br><em>NovaMart.</em></h1><p>从今天开始，发现那些让生活变得更好的小事。</p></div><form class="auth-card" novalidate @submit.prevent="submitAuth"><span class="overline">{{ ({ login: 'WELCOME BACK', register: 'JOIN NOVAMART', forgot: 'ACCOUNT RECOVERY', reset: 'RESET PASSWORD' })[authMode] }}</span><h2>{{ ({ login: '登录账户', register: '创建账户', forgot: '找回密码', reset: '重设密码' })[authMode] }}</h2><template v-if="['login', 'register'].includes(authMode)"><div class="auth-field"><label>用户名<input v-model.trim="authForm.username" type="text" inputmode="text" autocomplete="username" maxlength="30" :class="{ invalid: authFieldError('username') || authErrors.username }" @input="handleAuthInput('username', $event)" @compositionend="handleAuthInput('username', $event)"></label><p class="auth-rule">3-30 位，仅能使用英文字母、数字和下划线</p><p v-if="authFieldError('username') || authErrors.username" class="auth-error">{{ authFieldError('username') || authErrors.username }}</p></div></template><div v-if="['register', 'forgot'].includes(authMode)" class="auth-field"><label>邮箱<input v-model.trim="authForm.email" type="email" inputmode="email" autocomplete="email" maxlength="254" :class="{ invalid: authFieldError('email') || authErrors.email }" @input="handleAuthInput('email', $event)" @compositionend="handleAuthInput('email', $event)"></label><p class="auth-rule">使用标准英文邮箱，例如 name@example.com</p><p v-if="authFieldError('email') || authErrors.email" class="auth-error">{{ authFieldError('email') || authErrors.email }}</p></div><div v-if="['login', 'register'].includes(authMode)" class="auth-field"><label>密码<input v-model="authForm.password" type="password" :autocomplete="authMode === 'register' ? 'new-password' : 'current-password'" minlength="6" maxlength="64" :class="{ invalid: authFieldError('password') || authErrors.password }" @input="handleAuthInput('password', $event)" @compositionend="handleAuthInput('password', $event)"></label><p class="auth-rule">6-64 位，仅能使用英文、数字和符号</p><p v-if="authFieldError('password') || authErrors.password" class="auth-error">{{ authFieldError('password') || authErrors.password }}</p></div><template v-if="authMode === 'reset'"><div class="auth-field"><label>新密码<input v-model="authForm.newPassword" type="password" autocomplete="new-password" minlength="6" maxlength="64" :class="{ invalid: authFieldError('newPassword') || authErrors.newPassword }" @input="handleAuthInput('newPassword', $event)" @compositionend="handleAuthInput('newPassword', $event)"></label><p class="auth-rule">6-64 位，仅能使用英文、数字和符号</p><p v-if="authFieldError('newPassword') || authErrors.newPassword" class="auth-error">{{ authFieldError('newPassword') || authErrors.newPassword }}</p></div><div class="auth-field"><label>确认新密码<input v-model="authForm.confirmPassword" type="password" autocomplete="new-password" minlength="6" maxlength="64" :class="{ invalid: authFieldError('confirmPassword') || authErrors.confirmPassword }" @input="handleAuthInput('confirmPassword', $event)" @compositionend="handleAuthInput('confirmPassword', $event)"></label><p v-if="authFieldError('confirmPassword') || authErrors.confirmPassword" class="auth-error">{{ authFieldError('confirmPassword') || authErrors.confirmPassword }}</p></div></template><button class="primary wide" type="submit">{{ ({ login: '登录', register: '创建账户', forgot: '发送重设链接', reset: '确认重设密码' })[authMode] }} <span>→</span></button><button v-if="authMode === 'login'" class="text-button" type="button" @click="setAuthMode('forgot')">忘记密码？通过邮箱找回</button><button v-if="authMode === 'login' || authMode === 'register'" class="text-button" type="button" @click="switchAuthMode">{{ authMode === 'login' ? '还没有账户？立即注册' : '已经有账户？去登录' }}</button><button v-if="authMode === 'forgot' || authMode === 'reset'" class="text-button" type="button" @click="setAuthMode('login')">返回登录</button></form></section>
      <AdminCategories v-else-if="view === 'admin-categories' && isAdmin" :error-text="errorText" @updated="categoriesUpdated" @back="setView('admin-products')" />
      <section v-else-if="view === 'cart'" class="content-page"><div class="page-heading"><div><span class="overline">YOUR SELECTION</span><h1>购物袋</h1></div><button class="secondary" @click="setView('store')">继续探索 <span>→</span></button></div><div v-if="!cart.length" class="empty-state">购物袋还是空的。<button class="text-button" @click="setView('store')">去发现好物 →</button></div><div v-else class="cart-layout"><div class="cart-list"><article v-for="item in cart" :key="item.id" class="cart-item"><img :src="cover(item)" :alt="item.name" @error="imageFallback"><div class="cart-copy"><span class="category-label">{{ item.status === 'ON_SALE' ? '现货' : '已下架' }}</span><h2>{{ item.name }}</h2><strong>{{ formatMoney(item.price) }}</strong></div><div class="quantity"><button :aria-label="`减少${item.name}数量`" @click="updateQuantity(item,-1)">−</button><span>{{ item.quantity }}</span><button :aria-label="`增加${item.name}数量`" @click="updateQuantity(item,1)">+</button></div><strong class="line-total">{{ formatMoney(item.price * item.quantity) }}</strong><button class="text-button danger" @click="removeCart(item.id)">移除</button></article></div><aside class="summary"><span class="overline">ORDER SUMMARY</span><h2>订单摘要</h2><div><span>商品数量</span><strong>{{ cartCount }}</strong></div><div class="summary-total"><span>合计</span><strong>{{ formatMoney(cartTotal) }}</strong></div><button class="primary wide" @click="checkoutOpen=true">去结算 <span>→</span></button><p>提交订单后可在 15 分钟内完成付款。</p></aside></div></section>
      <section v-else-if="view === 'profile'" class="content-page profile-page">
        <div class="profile-intro">
          <div class="profile-avatar" aria-hidden="true">{{ (profileForm.username || user?.username || 'N').slice(0, 1).toUpperCase() }}</div>
          <div class="profile-intro-copy"><span class="overline">YOUR ACCOUNT</span><h1>个人资料</h1><p>管理你的账户信息与登录安全。</p></div>
          <span class="profile-role">{{ isAdmin ? '店长账户' : '顾客账户' }}</span>
        </div>
        <div class="profile-layout">
          <form class="profile-card" @submit.prevent="saveProfile">
            <div class="profile-card-heading"><div><span class="overline">01 / ACCOUNT</span><h2>基本信息</h2><p>更新你的用户名与联系邮箱。</p></div><span class="profile-card-mark">01</span></div>
            <p v-if="profileError" class="profile-message error" role="alert">{{ profileError }}</p>
            <label>用户名<input v-model.trim="profileForm.username" type="text" autocomplete="username" maxlength="30" required @input="sanitizeProfileInput('username', $event)" @compositionend="sanitizeProfileInput('username', $event)"><small>3-30 位，仅支持英文字母、数字和下划线</small></label>
            <label>邮箱<input v-model.trim="profileForm.email" type="text" inputmode="email" autocomplete="email" maxlength="254" required @input="sanitizeProfileInput('email', $event)" @compositionend="sanitizeProfileInput('email', $event)"><small>新邮箱不会经过邮件验证，请确认地址正确且属于你。</small></label>
            <label>当前密码<input v-model="profileForm.currentPassword" type="password" autocomplete="current-password" maxlength="64" required><small>用于确认是你本人在修改账户信息。</small></label>
            <button class="primary" :disabled="profileBusy">{{ profileBusy ? '正在保存…' : '保存个人资料' }} <span>→</span></button>
          </form>
          <form class="profile-card" @submit.prevent="savePassword">
            <div class="profile-card-heading"><div><span class="overline">02 / SECURITY</span><h2>修改密码</h2><p>定期更新密码，保护账户安全。</p></div><svg class="profile-lock-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="10" width="16" height="11" rx="1"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/></svg></div>
            <p v-if="passwordError" class="profile-message error" role="alert">{{ passwordError }}</p>
            <label>当前密码<input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" maxlength="64" required></label>
            <label>新密码<input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" minlength="6" maxlength="64" required><small>6-64 位，仅能使用英文、数字和符号</small></label>
            <label>确认新密码<input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" minlength="6" maxlength="64" required></label>
            <button class="primary" :disabled="passwordBusy">{{ passwordBusy ? '正在更新…' : '更新密码' }} <span>→</span></button>
            <p class="profile-security-note">更新后将退出当前账户，请用新密码重新登录。</p>
          </form>
        </div>
      </section>
      <section v-else-if="view === 'orders'" class="content-page orders-page">
        <div class="page-heading"><div><span class="overline">ORDER HISTORY</span><h1>我的订单</h1></div><div class="order-actions"><button class="secondary" @click="loadOrders">刷新订单</button><button class="secondary" @click="setView('store')">继续探索 <span>→</span></button></div></div>
        <div v-if="!orders.length" class="empty-state">暂时没有订单。</div>
        <div v-else class="order-list"><article v-for="order in orders" :key="order.id" class="order-card">
          <div class="order-top"><div><span>订单 #{{ order.id }}</span><small>{{ formatUtcTime(order.created_at) }}</small></div><span class="status" :class="order.status.toLowerCase()">{{ statusText(order.status) }}</span></div>
          <div class="order-body"><span>{{ order.recipient_name }} · {{ order.recipient_phone }} · {{ order.shipping_address }}</span><strong>{{ formatMoney(order.total_amount) }}</strong><div class="order-actions"><button class="secondary" @click="openOrder(order)">查看详情</button><button v-if="order.status === 'SHIPPED'" class="primary" :disabled="receiptBusy.includes(order.id)" @click="confirmReceipt(order)">{{ receiptBusy.includes(order.id) ? '正在确认…' : '确认收货' }}</button></div></div>
          <div v-if="order.delivered_cards" class="delivered-cards"><div class="delivered-cards-head"><strong>📦 自动发货卡密凭证</strong><div><button class="text-button" @click="toggleCards(order)">{{ cardsVisible(order) ? '隐藏卡密' : '显示卡密' }}</button><button class="text-button" @click="copyCards(order)">一键复制</button></div></div><pre>{{ cardsVisible(order) ? order.delivered_cards : '••••••••••••••••••••••••' }}</pre></div>
          <p v-if="['PAID','PROCESSING'].includes(order.status)" class="fulfillment-note">付款成功，商家正在处理订单，请等待发货。</p>
          <FulfillmentInfo :order="order" />
        </article></div>
      </section>
      <section v-else-if="view === 'order-detail' && detailOrder" class="content-page order-detail-page">
        <div class="page-heading"><div><span class="overline">ORDER #{{ detailOrder.order.id }}</span><h1>订单详情</h1></div><div class="order-actions"><button class="secondary" @click="openOrder(detailOrder.order)">刷新进度</button><button class="secondary" @click="setView('orders')">返回我的订单</button></div></div>
        <article class="order-detail-card">
          <div class="order-detail-status"><span class="status" :class="detailOrder.order.status.toLowerCase()">{{ statusText(detailOrder.order.status) }}</span><span v-if="detailOrder.order.status === 'PENDING_PAYMENT'" class="payment-deadline">请在 <strong>{{ formatCountdown(paymentSecondsLeft) }}</strong> 内完成付款，超时订单将自动取消</span><span v-else-if="detailOrder.order.status === 'CANCELLED'" class="payment-deadline">此订单已取消</span></div>
          <ol v-if="detailOrder.order.status !== 'CANCELLED'" class="order-progress" aria-label="订单进度"><li v-for="(step, index) in ['待付款','处理中','已发货','已完成']" :key="step" :class="{ reached: index <= ({ PENDING_PAYMENT: 0, PAID: 1, PROCESSING: 1, SHIPPED: 2, COMPLETED: 3 }[detailOrder.order.status] ?? -1) }">{{ step }}</li></ol>
          <p v-if="['PAID','PROCESSING'].includes(detailOrder.order.status)" class="fulfillment-note">付款成功，商家正在处理订单，请等待发货。</p>
          <FulfillmentInfo :order="detailOrder.order" />
          <div v-if="detailOrder.order.delivered_cards" class="delivered-cards"><div class="delivered-cards-head"><strong>📦 自动发货卡密凭证</strong><div><button class="text-button" @click="toggleCards(detailOrder.order)">{{ cardsVisible(detailOrder.order) ? '隐藏卡密' : '显示卡密' }}</button><button class="text-button" @click="copyCards(detailOrder.order)">一键复制</button></div></div><pre>{{ cardsVisible(detailOrder.order) ? detailOrder.order.delivered_cards : '••••••••••••••••••••••••' }}</pre></div>
          <p v-if="detailOrder.order.status === 'SHIPPED'" class="fulfillment-note">{{ detailOrder.order.delivery_type === 'VIRTUAL' ? '收到并核对虚拟卡密后，请确认收货。' : '收到商品后，请确认收货。' }}</p>
          <div class="delivery-details"><h2>收货信息</h2><p><span>收货人</span>{{ detailOrder.order.recipient_name }}</p><p><span>手机号</span>{{ detailOrder.order.recipient_phone }}</p><p><span>收货地址</span>{{ detailOrder.order.shipping_address }}</p></div>
          <div class="detail-items"><h2>商品清单</h2><div v-for="item in detailOrder.items" :key="item.id" class="detail-item"><img :src="cover(item)" :alt="item.product_name" @error="imageFallback"><span>{{ item.product_name }} × {{ item.quantity }}</span><strong>{{ formatMoney(item.subtotal) }}</strong></div></div>
          <div class="detail-total"><span>订单总额</span><strong>{{ formatMoney(detailOrder.order.total_amount) }}</strong></div>
          <div v-if="detailOrder.order.status === 'PENDING_PAYMENT'" class="wallet-payment">
            <div><strong>钱包余额支付</strong><span>当前可用：{{ walletBalance === null ? '暂时无法获取' : formatMoney(walletBalance) }}</span></div>
            <p v-if="!walletEnough">{{ walletBalance === null ? '请刷新余额后重试' : '余额不足，去充值后继续付款' }} <button class="text-button" @click="openWallet">我的钱包 →</button></p>
            <button class="primary wide" :disabled="!walletEnough || paymentBusy || paymentSecondsLeft <= 0" @click="payOrder">{{ paymentBusy ? '正在安全支付…' : paymentSecondsLeft <= 0 ? '付款已超时' : `确认支付 ${formatMoney(detailOrder.order.total_amount)}` }} <span>→</span></button>
          </div>
          <button v-if="detailOrder.order.status === 'SHIPPED'" class="primary wide" :disabled="receiptBusy.includes(detailOrder.order.id)" @click="confirmReceipt(detailOrder.order)">{{ receiptBusy.includes(detailOrder.order.id) ? '正在确认…' : '确认收货' }}</button>
        </article>
      </section>
      <section v-else-if="view === 'admin-site'" class="content-page narrow"><div class="page-heading"><div><span class="overline">HOME PAGE SETTINGS</span><h1>首页设置</h1></div><button class="secondary" @click="setView('store')">查看首页</button></div><form class="site-settings-card" @submit.prevent="saveSiteSettings"><div class="site-setting-block"><div><h2>首页主图</h2><p>首页右侧的大图。</p></div><label>图片地址（可选）<input v-model.trim="siteForm.heroImageUrl" @input="siteImagePreviews.hero = ''" type="text" inputmode="url" placeholder="https://... 或 /uploads/..."></label><label>上传本机图片<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="chooseSiteImage('hero', $event)"></label><img v-if="siteImagePreviews.hero || siteForm.heroImageUrl" class="site-image-preview" :src="siteImagePreviews.hero || siteForm.heroImageUrl" alt="首页主图预览" @error="imageFallback"></div><div class="site-setting-block"><div><h2>首页精选图</h2><p>首页下方编辑精选区域的图片。</p></div><label>图片地址（可选）<input v-model.trim="siteForm.editorialImageUrl" @input="siteImagePreviews.editorial = ''" type="text" inputmode="url" placeholder="https://... 或 /uploads/..."></label><label>上传本机图片<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="chooseSiteImage('editorial', $event)"></label><img v-if="siteImagePreviews.editorial || siteForm.editorialImageUrl" class="site-image-preview" :src="siteImagePreviews.editorial || siteForm.editorialImageUrl" alt="首页精选图预览" @error="imageFallback"></div><div class="form-actions"><button class="primary" type="submit">保存首页图片 <span>→</span></button></div></form></section>
<section v-else-if="view === 'admin-products'" class="content-page"><div class="page-heading"><div><span class="overline">CATALOG MANAGEMENT</span><h1>商品管理</h1></div><div class="catalog-management-actions"><button class="secondary" @click="setView('admin-categories')">管理分类</button><button class="primary" @click="newProduct">新增商品 <span>+</span></button></div></div><div class="data-panel"><table class="admin-products-table"><thead><tr><th>商品</th><th>分类</th><th>价格</th><th>库存</th><th>发货方式</th><th>本周精选</th><th>操作</th></tr></thead><tbody><tr v-for="product in adminProducts" :key="product.id"><td><strong>{{ product.name }}</strong><small>{{ product.description }}</small></td><td>{{ product.category_name || '-' }}</td><td>{{ formatMoney(product.price) }}</td><td>{{ product.stock }}</td><td>{{ product.delivery_type === 'AUTO_CARD' ? '自动卡密' : '实物/人工' }}</td><td><button type="button" class="featured-switch" :class="{ enabled: product.is_featured }" role="switch" :aria-checked="Boolean(product.is_featured)" :aria-label="`${product.name}本周精选`" :disabled="featuredBusy.includes(product.id)" @click="toggleFeatured(product)"><span class="featured-indicator" aria-hidden="true"><svg viewBox="0 0 16 16"><path d="m3.5 8 3 3 6-6"/></svg></span>{{ product.is_featured ? '已精选' : '未精选' }}</button><small v-if="product.is_featured && product.status !== 'ON_SALE'">在售后显示</small></td><td class="product-actions-cell"><div class="table-actions"><button class="text-button" @click="cardKeyProduct=product"><svg class="product-action-icon" viewBox="0 0 20 20" aria-hidden="true"><circle cx="6.5" cy="7" r="3.5"/><path d="m9 9.5 7 7m-2-2 2-2m-4 0 2-2"/></svg>卡密库存</button><button class="text-button" @click="editProduct(product)">编辑</button><button v-if="product.status === 'ON_SALE'" class="text-button danger" @click="unlistProduct(product)">下架</button></div></td></tr></tbody></table></div></section>
      <section v-else-if="view === 'admin-editor'" class="content-page narrow"><div class="page-heading"><div><span class="overline">CATALOG MANAGEMENT</span><h1>{{ editingProduct ? '编辑商品' : '新增商品' }}</h1></div><button class="secondary" @click="setView('admin-products')">返回列表</button></div><form class="editor-card" @submit.prevent="saveProduct"><label>商品名称<input v-model.trim="productForm.name" required maxlength="150"></label><label>商品分类<select v-model="productForm.categoryId"><option :value="null">未分类</option><option v-for="c in adminCategories" :key="c.id" :value="Number(c.id)">{{ c.name }}{{ c.is_visible ? '' : '（已隐藏）' }}</option></select><small>分类可在商品管理中的“管理分类”新增或调整。</small></label><label>价格<input v-model.number="productForm.price" min="0" step="0.01" required type="number"></label><label>库存<input v-model.number="productForm.stock" min="0" required type="number" :readonly="productForm.deliveryType === 'AUTO_CARD'"><small v-if="productForm.deliveryType === 'AUTO_CARD'">自动同步卡密库存，请在商品列表点击“卡密库存”补货。</small></label><label>发货方式<select v-model="productForm.deliveryType"><option value="PHYSICAL">实物 / 人工发货</option><option value="AUTO_CARD">自动卡密发货</option></select><small>自动卡密商品将在钱包付款成功后即时发货。</small></label><label>图片地址（可选）<input v-model.trim="productForm.imageUrl" type="text" inputmode="url" placeholder="https://... 或 /uploads/..."><small class="image-help">也可以选择电脑里的图片：</small><input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="chooseProductImage"></label><div v-if="productImagePreview" class="image-preview full"><img :src="productImagePreview" alt="商品图片预览"><span>已选择图片，保存商品后上传</span></div><label class="full">商品描述<textarea v-model.trim="productForm.description" rows="5"></textarea></label><label>销售状态<select v-model="productForm.status"><option value="ON_SALE">在售</option><option value="OFF_SALE">下架</option></select></label><label class="featured-option"><span>本周精选</span><span><input v-model="productForm.isFeatured" type="checkbox">加入本周精选</span><small>在售时显示于首页精选区，并标记为编辑推荐。</small></label><div class="form-actions"><button class="primary" type="submit">保存商品 <span>→</span></button></div></form></section>
      <section v-else-if="view === 'admin-orders'" class="content-page">
        <div class="page-heading"><div><span class="overline">ORDER MANAGEMENT</span><h1>订单管理</h1></div><button class="secondary" @click="loadAdminOrders">刷新订单</button></div>
        <div class="data-panel"><table class="admin-orders-table">
          <thead><tr><th>订单</th><th>用户名</th><th>收货人</th><th>手机号</th><th>收货地址</th><th>金额</th><th>状态</th><th>发货信息</th><th>操作</th></tr></thead>
          <tbody><tr v-for="order in adminOrders" :key="order.id">
            <td>#{{ order.id }}</td><td>{{ order.username || '未提供' }}</td>
            <td>{{ order.recipient_name && order.recipient_name !== '历史订单' ? order.recipient_name : '未填写' }}</td>
            <td class="admin-phone">{{ order.recipient_phone || '未填写' }}</td>
            <td class="admin-shipping">{{ order.shipping_address || '未填写' }}</td>
            <td>{{ formatMoney(order.total_amount) }}</td>
            <td><span class="status" :class="order.status.toLowerCase()">{{ statusText(order.status) }}</span></td>
            <td class="admin-fulfillment"><FulfillmentInfo v-if="['SHIPPED','COMPLETED'].includes(order.status)" :order="order" /><span v-else>—</span></td>
            <td><details v-if="order.delivered_cards" class="admin-delivered-cards"><summary>查看已发卡密</summary><pre>{{ order.delivered_cards }}</pre></details><button v-if="order.status === 'PAID'" class="secondary" @click="changeOrderStatus(order,'PROCESSING')">开始处理</button><template v-else-if="order.status === 'PROCESSING' && order.payment_status === 'PAID'"><button class="primary" @click="openShipping(order)">发货</button><button v-if="order.payment_method === 'WALLET' && !order.delivered_cards" class="text-button danger" @click="refundOrder(order)">全额退款</button></template><span v-else-if="order.status === 'SHIPPED'" class="fulfillment-note">等待顾客确认收货</span><span v-else>{{ order.payment_status === 'REFUNDED' ? '已退回钱包' : '—' }}</span></td>
          </tr><tr v-if="!adminOrders.length"><td colspan="9">暂无订单。</td></tr></tbody>
        </table></div>
      </section>
      <AdminWallet v-else-if="view === 'admin-wallet' && isAdmin" :error-text="errorText" @updated="loadWallet" />
      <AdminSupport v-else-if="view === 'admin-support' && isAdmin" />
      <section v-else-if="view === 'admin-stats'" class="content-page stats-page">
        <div class="page-heading stats-heading"><div><span class="overline">BUSINESS INSIGHTS</span><h1>经营数据</h1><p>按周期查看销售表现与商品贡献</p></div><button class="secondary stats-refresh" type="button" :disabled="statsBusy" @click="loadStats">{{ statsBusy ? '更新中…' : '刷新数据' }}</button></div>
        <div class="stats-range-panel">
          <div class="stats-toolbar"><div class="stats-range-tabs" role="tablist" aria-label="统计周期"><button v-for="option in [{key:'day',label:'按日'},{key:'week',label:'按周'},{key:'month',label:'按月'}]" :key="option.key" type="button" role="tab" :aria-selected="statsPeriod === option.key" :class="{active: statsPeriod === option.key}" @click="selectStatsPeriod(option.key)">{{ option.label }}</button></div><span class="stats-window">{{ statsPeriod === 'day' ? '最近 30 天' : statsPeriod === 'week' ? '最近 12 周' : '最近 12 个月' }}</span></div>
          <div class="stats-range-summary"><article class="stats-revenue"><span>期间销售额</span><strong>{{ formatMoney(statsTotals.amount) }}</strong><small>{{ statsPeriod === 'day' ? '最近 30 天累计' : statsPeriod === 'week' ? '最近 12 周累计' : '最近 12 个月累计' }}</small></article><article><span>付款订单</span><strong>{{ statsTotals.orders }}</strong><small>已付款订单</small></article><article><span>售出件数</span><strong>{{ statsTotals.items }}</strong><small>商品总数量</small></article></div>
          <div class="stats-content-grid">
            <section class="stats-chart-panel"><div class="stats-section-heading"><div><span class="overline">SALES TREND</span><h2>销售趋势</h2></div><span>{{ statsSeries.length }} 个周期</span></div><div v-if="statsSeries.length" class="stats-chart"><svg class="stats-line-chart" viewBox="0 0 720 250" role="img" :aria-label="`${statsWindowLabel}销售额趋势`" preserveAspectRatio="none"><defs><linearGradient id="sales-area-fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stop-color="var(--accent-color)" stop-opacity=".2"/><stop offset="100%" stop-color="var(--accent-color)" stop-opacity="0"/></linearGradient></defs><g v-for="tick in statsChartTicks" :key="tick.y"><line x1="54" x2="704" :y1="tick.y" :y2="tick.y" class="stats-chart-gridline"/><text x="45" :y="tick.y + 4" text-anchor="end" class="stats-chart-axis">{{ tick.value >= 1000 ? `${(tick.value / 1000).toFixed(tick.value % 1000 ? 1 : 0)}k` : Math.round(tick.value) }}</text></g><path :d="statsChartArea" class="stats-chart-area"/><path :d="statsChartLine" class="stats-chart-line"/><g v-for="point in statsChartMarkerPoints" :key="point.period_start"><circle :cx="point.x" :cy="point.y" r="3.5" class="stats-chart-point"><title>{{ statsPeriodLabel(point.period_start) }} · {{ formatMoney(point.value) }}</title></circle></g><g v-for="tick in statsChartLabels" :key="tick.x"><text :x="tick.x" y="238" text-anchor="middle" class="stats-chart-date">{{ tick.label }}</text></g></svg></div><div v-else class="stats-empty">所选期间暂无已付款订单。</div></section>
            <section class="stats-products-panel"><div class="stats-section-heading"><div><span class="overline">BEST SELLERS</span><h2>热销商品</h2></div><span>TOP {{ Math.min(topProducts.length, 8) }} · 按销量</span></div><div v-if="topProducts.length" class="stats-product-list"><article v-for="(item,index) in topProducts" :key="item.product_id" class="stats-product-row"><span class="stats-rank" :class="{ 'top-rank': index === 0 }">{{ String(index + 1).padStart(2,'0') }}</span><div class="stats-product-name"><strong>{{ item.product_name }}</strong><div class="stats-product-bar" aria-hidden="true"><i :style="{ width: `${Math.max(4, Number(item.sold_count) / Math.max(1, Number(topProducts[0].sold_count)) * 100)}%` }"></i></div><small>{{ item.sold_count }} 件售出</small></div><strong class="stats-product-revenue">{{ formatMoney(item.revenue) }}</strong></article></div><div v-else class="stats-empty">所选期间暂无商品销售数据。</div></section>
          </div>
          <div class="stats-table-wrap"><div class="stats-section-heading"><div><span class="overline">PERIOD BREAKDOWN</span><h2>周期明细</h2></div></div><table><thead><tr><th>{{ statsPeriod === 'day' ? '日期' : statsPeriod === 'week' ? '周起始日（周一）' : '月份' }}</th><th>付款订单</th><th>售出件数</th><th>销售额</th></tr></thead><tbody><tr v-for="item in sales" :key="item.period_start"><td>{{ statsPeriodLabel(item.period_start) }}</td><td>{{ item.order_count }}</td><td>{{ item.item_count }}</td><td>{{ formatMoney(item.sales_amount) }}</td></tr><tr v-if="!sales.length"><td colspan="4">所选期间暂无已付款订单数据</td></tr></tbody></table></div>
        </div>
        <!-- Future component boundary: extract this dashboard to AdminStats.vue with its data loading state. -->
        <section class="stats-overview" aria-labelledby="store-overview-heading"><div class="stats-section-heading"><div><span class="overline">STORE OVERVIEW</span><h2 id="store-overview-heading">店铺概况</h2></div><span>累计数据</span></div><div class="stats-overview-grid"><article><span>在售商品</span><strong>{{ overview.productCount || 0 }}</strong></article><article><span>历史订单</span><strong>{{ overview.orderCount || 0 }}</strong></article><article><span>累计已支付金额</span><strong>{{ formatMoney(overview.paidSales) }}</strong></article><article><span>顾客数量</span><strong>{{ overview.customerCount || 0 }}</strong></article></div></section>
      </section>
    </main>
    <footer class="site-footer"><div class="footer-brand"><span class="brand-symbol">N</span><strong>novamart.</strong><p>为生活挑选值得的东西。</p></div><div><span class="overline">EXPLORE</span><button @click="setView('store')">所有商品</button><button @click="setView('orders')">我的订单</button></div><div><span class="overline">SUPPORT</span><p>欢迎随时联系我们</p></div><small>© 2026 NovaMart / A curated shopping experience.</small></footer>
    <SupportWidget v-if="user && view !== 'admin-support'" :user="user" />
    <div v-if="shippingOrder" class="modal-backdrop" @click.self="!shippingBusy && (shippingOrder=null)">
      <form class="dialog shipping-dialog" role="dialog" aria-modal="true" aria-labelledby="shipping-title" @submit.prevent="submitShipping">
        <button class="modal-close" type="button" :disabled="shippingBusy" aria-label="关闭发货窗口" @click="shippingOrder=null">×</button>
        <span class="overline">ORDER #{{ shippingOrder.id }}</span><h1 id="shipping-title">订单发货</h1>
        <p>确认发货后，顾客可在“我的订单”查看以下信息。</p>
        <label>发货方式<select v-model="shippingForm.deliveryType" :disabled="shippingBusy" @change="shippingError=''"><option value="PHYSICAL">快递发货</option><option value="VIRTUAL">虚拟卡密</option></select></label>
        <label v-if="shippingForm.deliveryType === 'PHYSICAL'">快递单号<input v-model.trim="shippingForm.trackingNumber" :disabled="shippingBusy" maxlength="100" placeholder="请输入快递单号" required autocomplete="off"><small>支持英文字母、数字和短横线，最多 100 位</small></label>
        <label v-else>虚拟卡密<textarea v-model="shippingForm.virtualCode" :disabled="shippingBusy" maxlength="2000" rows="5" placeholder="请输入卡号、密码或兑换说明，可分行填写" required></textarea><small>最多 2000 个字符，仅该订单顾客和管理员可查看</small></label>
        <p v-if="shippingError" class="shipping-error" role="alert">{{ shippingError }}</p>
        <button class="primary wide" type="submit" :disabled="shippingBusy">{{ shippingBusy ? '正在提交…' : '确认发货' }}</button>
      </form>
    </div>
    <div v-if="activeProduct" class="modal-backdrop" @click.self="activeProduct=null"><article class="product-modal"><button class="modal-close" @click="activeProduct=null">×</button><img :src="cover(activeProduct)" :alt="activeProduct.name" @error="imageFallback"><div><span class="category-label">{{ activeProduct.category_name || '精选好物' }}</span><h1>{{ activeProduct.name }}</h1><p>{{ activeProduct.description || '为日常挑选的耐用品质。' }}</p><strong class="big-price">{{ formatMoney(activeProduct.price) }}</strong><p>库存 {{ activeProduct.stock }}</p><div v-if="cartQuantity(activeProduct) > 0" class="product-quantity" role="group" :aria-label="`${activeProduct.name}购物车数量`"><button type="button" :disabled="cartBusy(activeProduct)" aria-label="减少数量" @click="changeProductQuantity(activeProduct, -1)">−</button><span aria-live="polite">{{ cartQuantity(activeProduct) }}</span><button type="button" :disabled="cartBusy(activeProduct)" aria-label="增加数量" @click="changeProductQuantity(activeProduct, 1)">+</button></div><button v-else class="primary" :disabled="cartBusy(activeProduct)" @click="addToCart(activeProduct)">加入购物袋 <span>→</span></button></div></article></div>
    <div v-if="checkoutOpen" class="modal-backdrop" @click.self="checkoutOpen=false"><form class="dialog checkout-dialog" @submit.prevent="placeOrder"><button class="modal-close" type="button" @click="checkoutOpen=false">×</button><span class="overline">CHECKOUT</span><h1>确认订单</h1><p>订单金额 <strong class="big-price">{{ formatMoney(cartTotal) }}</strong></p><label>收货人姓名<input v-model.trim="recipient.recipientName" type="text" maxlength="80" autocomplete="name" placeholder="请输入收货人姓名" required></label><label>手机号<input v-model.trim="recipient.recipientPhone" type="tel" inputmode="numeric" maxlength="11" autocomplete="tel" placeholder="请输入 11 位手机号" required></label><label>收货地址<textarea v-model.trim="recipient.shippingAddress" maxlength="500" rows="3" autocomplete="street-address" placeholder="请输入详细收货地址" required></textarea></label><div class="wallet-payment"><strong>◉ 钱包余额支付</strong><p>当前可用：{{ walletBalance === null ? '请刷新后重试' : formatMoney(walletBalance) }}</p><p v-if="walletBalance !== null && Number(walletBalance) < cartTotal">余额不足，可先提交订单，再充值付款。<button type="button" class="text-button" @click="openWallet">去充值 →</button></p></div><p class="checkout-note">提交后订单进入待付款状态，不会立即扣款。请在 15 分钟内完成钱包支付，最终金额以服务器确认为准。</p><button class="primary wide" type="submit">提交订单 <span>→</span></button></form></div>
    <WalletPanel v-if="walletOpen && user" :balance="walletBalance" :error-text="errorText" @close="walletOpen=false" @updated="walletUpdated" />
    <AdminCardKeys v-if="cardKeyProduct && isAdmin" :product="cardKeyProduct" :error-text="errorText" @close="cardKeyProduct=null" @updated="loadAdminProducts" />
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>


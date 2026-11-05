<script setup>
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { formatUtcTime } from './dateTime'

const props = defineProps({ user: { type: Object, required: true } })
const api = inject('api')
const open = ref(false), tab = ref('chat'), sending = ref(false), busy = ref(false), error = ref('')
const config = ref({ adminOnline: false, aiEnabled: true, welcomeMessage: '' })
const messages = ref([]), tickets = ref([]), orders = ref([]), draft = ref('')
const unread = ref(0)
const ticketForm = ref({ category: 'OTHER', orderId: '', title: '', content: '' })
const selectedTicket = ref(null)
const messagePane = ref(null)
let poller = null
const statusLabel = computed(() => config.value.adminOnline ? '主理人在线 · 真人即时接待' : config.value.aiEnabled ? 'AI 买手值班中 · 24h 自动导购' : '店长暂不在线 · 留言后会尽快回复')
const quickQuestions = ['推荐 ¥100 内的好物', '店里有哪些现货？', '如何提取商品/售后？']

function explain(e) { return e?.response?.data?.message || e?.response?.data?.error || '操作失败，请稍后重试' }
async function scrollToLatest() {
  await nextTick()
  if (open.value && tab.value === 'chat' && messagePane.value) messagePane.value.scrollTop = messagePane.value.scrollHeight
}
async function updateMessages(latest, forceScroll = false) {
  const pane = messagePane.value
  const followLatest = forceScroll || !pane || pane.scrollHeight - pane.scrollTop - pane.clientHeight < 80
  messages.value = latest
  if (followLatest) await scrollToLatest()
}
async function initialize() {
  if (!props.user) return
  busy.value = true; error.value = ''
  try {
    const [init, ticketResult, orderResult] = await Promise.all([api.get('/support/init'), api.get('/support/tickets'), api.get('/orders')])
    config.value = init.data; await updateMessages(init.data.messages || []); unread.value = init.data.unread || 0; tickets.value = ticketResult.data; orders.value = orderResult.data
  } catch (e) { error.value = explain(e) } finally { busy.value = false }
}
async function refreshMessages(forceScroll = false) {
  if (!open.value || tab.value !== 'chat' || document.hidden) return
  try { await updateMessages((await api.get('/support/chat/messages')).data, forceScroll); unread.value = 0 } catch {}
}
async function pollStatus() {
  if (document.hidden) return
  try {
    const data = (await api.get('/support/init')).data
    config.value = data; unread.value = data.unread || 0
    if (open.value && tab.value === 'chat') { await updateMessages(data.messages || []); await refreshMessages() }
  } catch {}
}
function startPolling() { stopPolling(); poller = window.setInterval(() => { pollStatus(); if (open.value && tab.value === 'tickets' && !document.hidden) loadTickets() }, 3000) }
function stopPolling() { if (poller) window.clearInterval(poller); poller = null }
watch(() => props.user?.userId ?? props.user?.id, initialize, { immediate: true })
watch(() => [open.value, tab.value], async () => { if (open.value && tab.value === 'chat') { await scrollToLatest(); await refreshMessages(true) } })
watch(() => props.user?.userId ?? props.user?.id, (id) => { if (id) startPolling(); else stopPolling() }, { immediate: true })
onBeforeUnmount(stopPolling)

function onMessageKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (!event.repeat) send()
}
async function send(text = draft.value) {
  const content = text.trim(); if (!content || sending.value || content.length > 150) return
  sending.value = true; error.value = ''; draft.value = content === draft.value ? '' : draft.value
  try { const result = (await api.post('/support/chat/send', { content })).data; await updateMessages(result.messages || messages.value, true) }
  catch (e) { error.value = explain(e) }
  finally { sending.value = false }
}
async function loadTickets() { try { const [result, orderResult] = await Promise.all([api.get('/support/tickets'), api.get('/orders')]); tickets.value = result.data; orders.value = orderResult.data; if (selectedTicket.value) selectedTicket.value = tickets.value.find(t => t.id === selectedTicket.value.id) || null } catch (e) { error.value = explain(e) } }
async function submitTicket() {
  if (!ticketForm.value.title.trim() || !ticketForm.value.content.trim()) return
  sending.value = true; error.value = ''
  try { await api.post('/support/tickets', { ...ticketForm.value, orderId: ticketForm.value.orderId || null }); ticketForm.value = { category: 'OTHER', orderId: '', title: '', content: '' }; selectedTicket.value = null; await loadTickets() }
  catch (e) { error.value = explain(e) } finally { sending.value = false }
}
async function closeTicket(ticket) { try { await api.put(`/support/tickets/${ticket.id}/close`); await loadTickets(); selectedTicket.value = null } catch (e) { error.value = explain(e) } }
function time(value) { return formatUtcTime(value, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function status(value) { return ({ OPEN: '待处理', REPLIED: '店长已回复', CLOSED: '已结单' })[value] || value }
</script>

<template>
  <div class="support-widget">
    <button v-if="!open" class="support-launcher" type="button" aria-label="打开导购与客服" @click="open = true">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H6l-3 2v-6.5A7.5 7.5 0 1 1 20 11.5Z"/><path d="M8 11h.01M12 11h.01M16 11h.01"/></svg><span>导购 / 客服</span><i v-if="unread" class="support-launcher-dot"></i>
    </button>
    <section v-else class="support-panel" aria-label="NovaMart 客服中心">
      <header class="support-head"><div><span class="support-kicker">NOVAMART · CARE</span><h2>有什么可以帮您？</h2><p><i :class="{ online: config.adminOnline }"></i>{{ statusLabel }}</p></div><button type="button" class="support-close" aria-label="收起客服面板" @click="open = false">×</button></header>
      <nav class="support-tabs"><button :class="{ active: tab === 'chat' }" @click="tab = 'chat'">即时对话</button><button :class="{ active: tab === 'tickets' }" @click="tab = 'tickets'; loadTickets()">售后工单 <span v-if="tickets.some(t => t.status === 'REPLIED')" class="support-dot"></span></button></nav>
      <div v-if="error" class="support-error" role="alert">{{ error }}</div>
      <template v-if="tab === 'chat'">
        <div ref="messagePane" class="support-messages" aria-live="polite">
          <p v-if="!messages.length" class="support-welcome">{{ config.welcomeMessage || '您好，欢迎来到 NovaMart。今天想为日常添置点什么好物？' }}</p>
          <article v-for="message in messages" :key="message.id" class="support-message" :class="message.sender_role.toLowerCase()"><small v-if="message.sender_role !== 'USER'">{{ message.sender_role === 'AI' ? '✦ AI 买手' : '主理人' }}</small><p>{{ message.content }}</p><time>{{ time(message.created_at) }}</time></article>
          <p v-if="sending && !config.adminOnline && config.aiEnabled" class="support-thinking">正在为您挑选与思考…</p>
        </div>
<div class="support-compose"><div class="support-quick"><button v-for="q in quickQuestions" :key="q" @click="send(q)">{{ q }}</button></div><form @submit.prevent="send()"><textarea v-model="draft" maxlength="150" rows="2" placeholder="说说您正在寻找什么…" :disabled="sending" @keydown="onMessageKeydown"></textarea><div><small>{{ draft.length }}/150</small><button class="support-send" type="submit" :disabled="sending || !draft.trim()">发送 ↗</button></div></form></div>
      </template>
      <template v-else>
        <div v-if="selectedTicket" class="support-ticket-detail"><button class="support-back" @click="selectedTicket = null">← 返回工单列表</button><span class="support-kicker">{{ selectedTicket.ticket_no }}</span><h3>{{ selectedTicket.title }}</h3><p>{{ selectedTicket.content }}</p><div v-if="selectedTicket.admin_reply" class="support-reply"><small>店长回复</small><p>{{ selectedTicket.admin_reply }}</p></div><button v-if="selectedTicket.status !== 'CLOSED'" class="support-outline" @click="closeTicket(selectedTicket)">问题已解决，关闭工单</button></div>
        <div v-else class="support-ticket-scroll"><div class="support-ticket-list"><button v-for="ticket in tickets" :key="ticket.id" class="support-ticket-row" @click="selectedTicket = ticket"><span><b>{{ ticket.title }}</b><small>{{ ticket.ticket_no }} · {{ time(ticket.created_at) }}</small></span><em :class="ticket.status.toLowerCase()">{{ status(ticket.status) }}</em></button><p v-if="!tickets.length" class="support-empty">还没有售后工单</p></div>
          <form class="support-ticket-form" @submit.prevent="submitTicket"><h3>提交新工单</h3><label>问题分类<select v-model="ticketForm.category"><option value="PRE_SALE">售前咨询</option><option value="CARD_ISSUE">卡密与发货问题</option><option value="PAYMENT">支付与充值问题</option><option value="OTHER">其他</option></select></label><label>关联订单<select v-model="ticketForm.orderId"><option value="">不关联订单</option><option v-for="order in orders" :key="order.id" :value="String(order.id)">订单 #{{ order.id }} · {{ order.status }}</option></select></label><label>问题简述<input v-model.trim="ticketForm.title" maxlength="120" required placeholder="请简要描述问题"></label><label>详细说明<textarea v-model.trim="ticketForm.content" maxlength="3000" rows="3" required placeholder="补充说明，便于店长快速处理"></textarea></label><button class="support-send" :disabled="sending">{{ sending ? '正在提交…' : '提交工单 ↗' }}</button></form>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.support-widget{position:fixed;right:24px;bottom:24px;z-index:90;font-family:inherit;color:var(--text-primary)}.support-launcher{position:relative;display:flex;align-items:center;gap:9px;border:0;border-radius:999px;padding:13px 18px;background:var(--accent-color);color:var(--text-on-accent);box-shadow:0 10px 32px #302b2530;font:inherit;font-weight:600;cursor:pointer}.support-launcher-dot{position:absolute;right:3px;top:2px;width:10px;height:10px;border:2px solid var(--bg-primary);border-radius:50%;background:#d64a3b}.support-launcher svg{width:21px;height:21px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.support-panel{width:min(390px,calc(100vw - 28px));height:min(650px,calc(100vh - 48px));display:flex;flex-direction:column;background:var(--bg-card);border:1px solid var(--border-color);box-shadow:0 18px 60px #201a1630}.support-head{display:flex;justify-content:space-between;align-items:flex-start;padding:20px 20px 14px;border-bottom:1px solid var(--border-color)}.support-kicker{font-size:10px;letter-spacing:.12em;color:var(--text-muted)}.support-head h2{font:26px/1.1 Georgia,serif;margin:8px 0}.support-head p{font-size:12px;color:var(--text-secondary);margin:0}.support-head p i{display:inline-block;width:7px;height:7px;border-radius:50%;margin-right:7px;background:var(--accent-color)}.support-head p i.online{background:#54836a}.support-close{border:0;background:none;color:var(--text-secondary);font-size:26px;cursor:pointer}.support-tabs{display:flex;border-bottom:1px solid var(--border-color)}.support-tabs button{position:relative;flex:1;padding:12px;border:0;background:none;color:var(--text-secondary);font:inherit;font-size:13px;cursor:pointer}.support-tabs button.active{color:var(--accent-color)}.support-tabs button.active:after{content:'';position:absolute;left:18%;right:18%;bottom:-1px;height:2px;background:var(--accent-color)}.support-dot{display:inline-block;width:6px;height:6px;background:var(--accent-color);border-radius:50%}.support-messages{flex:1;overflow:auto;padding:16px;display:flex;flex-direction:column;gap:12px}.support-welcome{align-self:flex-start;max-width:85%;margin:0;background:var(--bg-secondary);padding:11px 13px;font-size:13px;line-height:1.6}.support-message{max-width:84%;align-self:flex-start}.support-message.user{align-self:flex-end}.support-message small{display:block;color:var(--text-muted);font-size:10px;margin:0 0 4px}.support-message p{margin:0;padding:10px 12px;background:var(--bg-secondary);font-size:13px;line-height:1.55;white-space:pre-wrap;overflow-wrap:anywhere}.support-message.user p{background:var(--accent-color);color:var(--text-on-accent)}.support-message time{display:block;margin-top:4px;color:var(--text-muted);font-size:9px}.support-message.user time{text-align:right}.support-thinking{align-self:flex-start;color:var(--text-muted);font-size:12px;animation:pulse 1.5s infinite}@keyframes pulse{50%{opacity:.45}}.support-compose{padding:10px 14px 14px;border-top:1px solid var(--border-color)}.support-quick{display:flex;gap:6px;overflow:auto;padding:0 0 9px}.support-quick button{white-space:nowrap;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-secondary);padding:5px 8px;font:inherit;font-size:10px;cursor:pointer}.support-compose form{border:1px solid var(--border-color);padding:8px}.support-compose textarea,.support-ticket-form textarea{resize:vertical}.support-compose textarea{width:100%;border:0;outline:0;background:transparent;color:var(--text-primary);font:inherit;font-size:13px}.support-compose form div{display:flex;justify-content:space-between;align-items:center}.support-compose small{color:var(--text-muted)}.support-send{border:0;background:var(--accent-color);color:var(--text-on-accent);padding:8px 13px;font:inherit;font-size:12px;cursor:pointer}.support-send:disabled{opacity:.5;cursor:not-allowed}.support-error{padding:8px 14px;background:var(--bg-secondary);color:var(--accent-color);font-size:12px}.support-ticket-scroll{flex:1;overflow:auto}.support-ticket-list{padding:6px 15px}.support-ticket-row{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;padding:12px 2px;border:0;border-bottom:1px solid var(--border-color);background:none;color:var(--text-primary);text-align:left;cursor:pointer}.support-ticket-row span{min-width:0}.support-ticket-row b,.support-ticket-row small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.support-ticket-row b{font-size:13px;font-weight:500}.support-ticket-row small{margin-top:5px;color:var(--text-muted);font-size:10px}.support-ticket-row em{font-style:normal;font-size:10px;color:var(--accent-color);white-space:nowrap}.support-ticket-row em.closed{color:var(--text-muted)}.support-empty{padding:16px 0;color:var(--text-muted);font-size:12px}.support-ticket-form{padding:14px 16px 18px;border-top:1px solid var(--border-color);display:grid;gap:10px}.support-ticket-form h3,.support-ticket-detail h3{margin:0;font:20px Georgia,serif}.support-ticket-form label{display:grid;gap:5px;font-size:11px;color:var(--text-secondary)}.support-ticket-form input,.support-ticket-form select,.support-ticket-form textarea{width:100%;padding:8px;border:1px solid var(--border-color);background:var(--bg-primary);color:var(--text-primary);font:inherit;font-size:12px}.support-ticket-detail{padding:18px;overflow:auto}.support-back{border:0;background:none;color:var(--accent-color);padding:0 0 18px;font:inherit;font-size:12px;cursor:pointer}.support-ticket-detail h3{margin:8px 0}.support-ticket-detail>p,.support-reply p{font-size:13px;line-height:1.65;white-space:pre-wrap}.support-reply{margin:18px 0;padding:12px;background:var(--bg-secondary)}.support-reply small{color:var(--text-muted);font-size:11px}.support-outline{border:1px solid var(--border-color);padding:9px 12px;background:none;color:var(--text-secondary);font:inherit;font-size:12px;cursor:pointer}@media(max-width:520px){.support-widget{right:12px;bottom:12px}.support-panel{height:min(690px,calc(100dvh - 24px))}.support-launcher{padding:12px 15px}}
/* Keep checkout, product dialogs and the wallet above the floating help panel. */
.support-widget{z-index:9}
</style>

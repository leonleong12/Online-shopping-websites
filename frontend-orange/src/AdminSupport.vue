<script setup>
import { inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { formatUtcTime } from './dateTime'

const api = inject('api')
const config = ref({ adminOnline: false, aiEnabled: true, aiBaseUrl: 'https://api.aijws.com/v1', aiProtocol: 'RESPONSES', aiModel: 'qwen3.8-flash', welcomeMessage: '', aiApiKey: '', apiKeyConfigured: false })
const models = ref([]), modelsLoading = ref(false), modelsNotice = ref(''), modelsError = ref('')
let modelsRequest = 0
watch(() => [config.value.aiBaseUrl, config.value.aiApiKey, config.value.aiProtocol], () => {
  modelsRequest++; models.value = []; modelsNotice.value = ''; modelsError.value = ''; modelsLoading.value = false
})
function aiParams() { return { aiBaseUrl: config.value.aiBaseUrl, aiProtocol: config.value.aiProtocol, aiModel: config.value.aiModel, aiApiKey: config.value.aiApiKey || null } }
async function fetchModels() {
  if (modelsLoading.value) return
  const request = ++modelsRequest
  modelsLoading.value = true; modelsNotice.value = ''; modelsError.value = ''
  try {
    const result = (await api.post('/admin/support/models', aiParams())).data
    if (request !== modelsRequest) return
    models.value = result.models || []; modelsNotice.value = result.message
  } catch (e) { if (request === modelsRequest) modelsError.value = explain(e) }
  finally { if (request === modelsRequest) modelsLoading.value = false }
}
const sessions = ref([]), selectedSession = ref(null), messages = ref([]), tickets = ref([]), selectedTicket = ref(null)
const messagePane = ref(null)
const content = ref(''), loading = ref(false), error = ref(''), notice = ref(''), mode = ref('sessions'), filter = ref('OPEN')
let timer = null
const emit = defineEmits(['updated'])
function explain(e) { return e?.response?.data?.message || e?.response?.data?.error || '操作失败，请稍后重试' }
async function loadConfig() { try { config.value = { ...config.value, ...(await api.get('/admin/support/config')).data, aiApiKey: '' } } catch (e) { error.value = explain(e) } }
async function loadSessions() { try { sessions.value = (await api.get('/admin/support/sessions')).data } catch (e) { error.value = explain(e) } }
async function scrollToLatest() {
  await nextTick()
  if (messagePane.value) messagePane.value.scrollTop = messagePane.value.scrollHeight
}
async function loadMessages(forceScroll = false) {
  const sessionId = selectedSession.value?.id
  if (!sessionId) return
  const pane = messagePane.value
  const followLatest = forceScroll || !pane || pane.scrollHeight - pane.scrollTop - pane.clientHeight < 80
  try {
    const result = (await api.get(`/admin/support/sessions/${sessionId}/messages`)).data
    if (selectedSession.value?.id !== sessionId) return
    messages.value = result
    if (followLatest) await scrollToLatest()
    await loadSessions()
  } catch (e) { error.value = explain(e) }
}
async function selectSession(session) { selectedSession.value = session; await loadMessages(true) }
async function loadTickets() { try { tickets.value = (await api.get('/admin/support/tickets', { params: { status: filter.value || undefined } })).data } catch (e) { error.value = explain(e) } }
async function refresh() { error.value = ''; if (mode.value === 'sessions') { await loadSessions(); if (selectedSession.value) await loadMessages() } else await loadTickets() }
onMounted(async () => { await Promise.all([loadConfig(), loadSessions()]); timer = window.setInterval(() => { if (document.hidden) return; if (mode.value === 'sessions') { if (selectedSession.value) loadMessages(); else loadSessions() } else if (!selectedTicket.value) loadTickets() }, 3000) })
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
async function toggleOnline() { if (loading.value) return; config.value.adminOnline = !config.value.adminOnline; await saveConfig() }
async function saveConfig() {
  loading.value = true; error.value = ''; notice.value = ''
  try { await api.put('/admin/support/config', { adminOnline: config.value.adminOnline, aiEnabled: config.value.aiEnabled, welcomeMessage: config.value.welcomeMessage, ...aiParams() }); config.value.aiApiKey = ''; await loadConfig(); notice.value = '客服配置已保存' }
  catch (e) { error.value = explain(e); await loadConfig() } finally { loading.value = false }
}
async function testAi() { loading.value = true; error.value = ''; notice.value = ''; try { const result = (await api.post('/admin/support/test-ai', aiParams())).data; notice.value = `${result.message}：${result.reply}（测试当前填写的参数；修改后请保存配置）` } catch (e) { error.value = explain(e) } finally { loading.value = false } }
function onReplyKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (!event.repeat) sendReply()
}
async function sendReply() {
  if (loading.value || !content.value.trim() || !selectedSession.value) return
  const sessionId = selectedSession.value.id
  loading.value = true; error.value = ''
  try {
    const result = (await api.post(`/admin/support/sessions/${sessionId}/messages`, { content: content.value.trim() })).data
    if (selectedSession.value?.id === sessionId) {
      messages.value = result.messages; content.value = ''
      await scrollToLatest()
    }
    await loadSessions()
  } catch (e) { error.value = explain(e) }
  finally { loading.value = false }
}
async function replyTicket(status = 'REPLIED') { if (!selectedTicket.value?.draftReply?.trim()) return; loading.value = true; error.value = ''; try { await api.put(`/admin/support/tickets/${selectedTicket.value.id}`, { adminReply: selectedTicket.value.draftReply.trim(), status }); selectedTicket.value = null; notice.value = '工单处理结果已保存'; await loadTickets() } catch (e) { error.value = explain(e) } finally { loading.value = false } }
function time(value) { return formatUtcTime(value, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function role(value) { return value === 'USER' ? '顾客' : value === 'AI' ? 'AI 买手' : '主理人' }
function status(value) { return ({ OPEN: '待处理', REPLIED: '店长已回复', CLOSED: '已结单' })[value] || value }
</script>

<template>
  <section class="content-page admin-support">
    <div class="page-heading"><div><span class="overline">CUSTOMER CARE</span><h1>客服与工单</h1></div><button class="secondary" @click="refresh">刷新工作台</button></div>
    <div v-if="error" class="support-admin-alert error" role="alert">{{ error }}</div><div v-if="notice" class="support-admin-alert">{{ notice }}</div>
    <section class="support-config">
      <div class="support-mode"><div><span class="overline">接待模式</span><h2>{{ config.adminOnline ? '真人接待中' : 'AI 自动导购' }}</h2><p>{{ config.adminOnline ? '新消息将进入下方会话列表，AI 不会自动回复。' : '店长离线时，由 AI 买手自动接待。' }}</p></div><button class="support-mode-toggle" :class="{ active: config.adminOnline }" @click="toggleOnline"><i></i>{{ config.adminOnline ? '我在线 · 真人接管' : '我离线 · 交给 AI' }}</button></div>
      <details class="support-settings">
        <summary>AI 导购参数配置 <span>{{ config.apiKeyConfigured ? 'API Key 已配置' : '尚未配置 API Key' }}</span></summary>
        <form @submit.prevent="saveConfig">
          <label>API 地址<input v-model.trim="config.aiBaseUrl" type="url" required></label>
          <label>API 协议<select v-model="config.aiProtocol" aria-label="API 协议"><option value="CHAT_COMPLETIONS">OpenAI Chat Completions</option><option value="RESPONSES">OpenAI Responses</option><option value="ANTHROPIC_MESSAGES">Anthropic Messages</option></select></label>
          <label>API Key<input v-model="config.aiApiKey" type="password" autocomplete="new-password" :placeholder="config.apiKeyConfigured ? '已配置，留空则保持不变' : '请输入服务端 API Key'"></label>
          <div class="support-model-field">
            <label for="ai-model">模型</label>
            <div class="support-model-row"><input id="ai-model" v-model.trim="config.aiModel" type="text" maxlength="100" placeholder="请输入模型名称" required><button class="secondary" type="button" :disabled="modelsLoading || loading || !config.aiBaseUrl || (!config.apiKeyConfigured && !config.aiApiKey)" @click="fetchModels">{{ modelsLoading ? '获取中…' : '获取可用模型' }}</button></div>
            <select v-if="models.length" aria-label="获取的模型列表" :value="models.some(model => model.id === config.aiModel) ? config.aiModel : ''" @change="config.aiModel = $event.target.value"><option value="" disabled>从列表选择，也可手动输入</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name === model.id ? model.id : `${model.name} · ${model.id}` }}</option></select>
            <small v-if="modelsNotice" class="support-model-notice" role="status">{{ modelsNotice }}</small>
            <small v-if="modelsError" class="support-model-error" role="alert">{{ modelsError }}</small>
          </div>
          <label>欢迎语<input v-model.trim="config.welcomeMessage" maxlength="500"></label>
          <label class="support-ai-switch"><input v-model="config.aiEnabled" type="checkbox">启用 AI 导购</label>
          <div class="support-form-actions"><button class="secondary" type="button" :disabled="loading" @click="testAi">测试 AI 对话</button><button class="primary" type="submit" :disabled="loading">保存配置</button></div>
        </form>
      </details>
    </section>
    <nav class="support-admin-tabs"><button :class="{ active: mode === 'sessions' }" @click="mode='sessions'; selectedTicket=null; loadSessions()">即时会话接待</button><button :class="{ active: mode === 'tickets' }" @click="mode='tickets'; selectedSession=null; loadTickets()">工单处理中心 <b v-if="tickets.some(t => t.status === 'OPEN')">{{ tickets.filter(t => t.status === 'OPEN').length }}</b></button></nav>
<div v-if="mode === 'sessions'" class="support-workbench"><aside class="support-session-list"><button v-for="session in sessions" :key="session.id" :class="{ selected: selectedSession?.id === session.id }" @click="selectSession(session)"><span><b>{{ session.username }}</b><i v-if="session.unread_for_admin" class="support-unread">{{ session.unread_for_admin }}</i></span><small>{{ session.last_message || '尚无消息' }}</small><time>{{ time(session.updated_at) }}</time></button><p v-if="!sessions.length" class="support-empty">还没有顾客联系</p></aside><section class="support-conversation"><template v-if="selectedSession"><header><div><span class="overline">顾客会话</span><h2>{{ selectedSession.username }}</h2></div><span class="support-session-state">{{ config.adminOnline ? '真人接待' : 'AI 值班中' }}</span></header><div ref="messagePane" class="support-admin-messages"><article v-for="message in messages" :key="message.id" :class="message.sender_role.toLowerCase()"><small>{{ role(message.sender_role) }} · {{ time(message.created_at) }}</small><p>{{ message.content }}</p></article><p v-if="!messages.length" class="support-empty">会话暂无消息</p></div><form class="support-admin-reply" @submit.prevent="sendReply"><textarea v-model="content" maxlength="150" rows="2" placeholder="以主理人身份回复，最多 150 字" @keydown="onReplyKeydown" required></textarea><div><small>{{ content.length }}/150</small><button class="primary" :disabled="loading || !content.trim()">发送回复 ↗</button></div></form></template><div v-else class="support-select-prompt">选择一位顾客，查看完整对话</div></section></div>
    <div v-else class="support-ticket-workbench"><section v-if="!selectedTicket" class="support-ticket-admin-list"><div class="support-ticket-toolbar"><h2>售后工单</h2><select v-model="filter" @change="loadTickets"><option value="OPEN">待处理</option><option value="REPLIED">店长已回复</option><option value="CLOSED">已结单</option><option value="">全部状态</option></select></div><div class="data-panel"><table><thead><tr><th>工单号</th><th>顾客</th><th>分类</th><th>问题</th><th>关联订单</th><th>状态</th><th>提交时间</th><th></th></tr></thead><tbody><tr v-for="ticket in tickets" :key="ticket.id"><td>{{ ticket.ticket_no }}</td><td>{{ ticket.username }}</td><td>{{ ({ PRE_SALE:'售前咨询', CARD_ISSUE:'卡密与发货', PAYMENT:'支付与充值', OTHER:'其他' })[ticket.category] }}</td><td>{{ ticket.title }}</td><td>{{ ticket.related_order_no || '—' }}</td><td>{{ status(ticket.status) }}</td><td>{{ time(ticket.created_at) }}</td><td><button class="text-button" @click="selectedTicket={...ticket,draftReply:ticket.admin_reply||''}">查看 / 处理</button></td></tr><tr v-if="!tickets.length"><td colspan="8">当前筛选下没有工单</td></tr></tbody></table></div></section><section v-else class="support-ticket-detail-admin"><button class="support-back" @click="selectedTicket=null">← 返回工单列表</button><span class="overline">{{ selectedTicket.ticket_no }} · {{ status(selectedTicket.status) }}</span><h2>{{ selectedTicket.title }}</h2><p class="support-ticket-meta">{{ selectedTicket.username }} · {{ time(selectedTicket.created_at) }} · {{ selectedTicket.related_order_no || '未关联订单' }}</p><article>{{ selectedTicket.content }}</article><label>处理结果<textarea v-model.trim="selectedTicket.draftReply" maxlength="3000" rows="6" placeholder="填写给顾客的处理结果"></textarea></label><div class="support-form-actions"><button class="secondary" @click="selectedTicket=null">取消</button><button class="primary" :disabled="loading || !selectedTicket.draftReply.trim()" @click="replyTicket('REPLIED')">保存并回复</button><button class="secondary" :disabled="loading || !selectedTicket.draftReply.trim()" @click="replyTicket('CLOSED')">回复并结单</button></div></section></div>
  </section>
</template>

<style scoped>
.support-settings form>label{align-content:start}
.support-model-field{display:grid;align-content:start;gap:6px;min-width:0}.support-model-row{display:flex;gap:8px;min-width:0}.support-model-row input{min-width:0;flex:1}.support-model-row button{flex-shrink:0;white-space:nowrap;font-size:12px;padding:9px 10px}.support-model-notice,.support-model-error{font-size:11px;line-height:1.6;overflow-wrap:anywhere}.support-model-notice{color:var(--text-muted)}.support-model-error{color:var(--accent-color)}.support-model-field select{min-width:0}.support-settings form>label{min-width:0}.support-settings select{min-width:0}
.admin-support{--support-space:16px}.support-admin-alert{margin:0 0 12px;padding:11px 14px;border:1px solid var(--border-color);color:var(--text-secondary);font-size:13px}.support-admin-alert.error{color:var(--accent-color)}.support-config{border:1px solid var(--border-color);background:var(--bg-card)}.support-mode{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px}.support-mode h2{font:26px Georgia,serif;margin:7px 0}.support-mode p{margin:0;color:var(--text-secondary);font-size:13px}.support-mode-toggle{display:flex;align-items:center;gap:9px;padding:10px 14px;border:1px solid var(--border-color);background:var(--bg-primary);color:var(--text-secondary);font:inherit;cursor:pointer}.support-mode-toggle i{width:9px;height:9px;background:var(--text-muted);border-radius:50%}.support-mode-toggle.active{color:#47785c;border-color:#47785c}.support-mode-toggle.active i{background:#54836a}.support-settings{border-top:1px solid var(--border-color)}.support-settings summary{padding:13px 20px;cursor:pointer;font-size:13px}.support-settings summary span{float:right;color:var(--text-muted);font-size:11px}.support-settings form{padding:4px 20px 18px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.support-settings label,.support-ticket-detail-admin label{display:grid;gap:6px;color:var(--text-secondary);font-size:12px}.support-settings input:not([type=checkbox]),.support-settings select,.support-ticket-detail-admin textarea{width:100%;padding:10px;border:1px solid var(--border-color);background:var(--bg-primary);color:var(--text-primary);font:inherit}.support-ai-switch{display:flex!important;align-items:center;gap:8px}.support-form-actions{display:flex;justify-content:flex-end;gap:9px;align-items:center}.support-settings .support-form-actions{grid-column:1/-1}.support-admin-tabs{display:flex;gap:22px;border-bottom:1px solid var(--border-color);margin-top:24px}.support-admin-tabs button{position:relative;padding:13px 2px;border:0;background:none;color:var(--text-secondary);font:inherit;cursor:pointer}.support-admin-tabs button.active{color:var(--accent-color)}.support-admin-tabs button.active:after{content:'';position:absolute;left:0;right:0;bottom:-1px;height:2px;background:var(--accent-color)}.support-admin-tabs b{margin-left:6px;color:var(--accent-color);font-size:11px}.support-workbench{display:grid;grid-template-columns:minmax(230px,30%) 1fr;min-height:490px;border:1px solid var(--border-color);border-top:0;background:var(--bg-card)}.support-session-list{border-right:1px solid var(--border-color);overflow:auto}.support-session-list>button{width:100%;padding:14px;border:0;border-bottom:1px solid var(--border-color);background:none;text-align:left;color:var(--text-primary);cursor:pointer}.support-session-list>button.selected{background:var(--bg-secondary)}.support-session-list>button span{display:flex;justify-content:space-between}.support-session-list b{font-weight:500}.support-session-list small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:7px;color:var(--text-secondary)}.support-session-list time{display:block;margin-top:5px;color:var(--text-muted);font-size:10px}.support-unread{font-style:normal;min-width:18px;height:18px;line-height:18px;text-align:center;border-radius:99px;background:var(--accent-color);color:white;font-size:10px}.support-conversation{display:flex;min-width:0;flex-direction:column}.support-conversation>header{display:flex;justify-content:space-between;align-items:center;padding:15px 18px;border-bottom:1px solid var(--border-color)}.support-conversation h2{margin:5px 0 0;font:22px Georgia,serif}.support-session-state{color:var(--text-muted);font-size:11px}.support-admin-messages{flex:1;max-height:420px;overflow:auto;padding:18px;display:flex;flex-direction:column;gap:13px}.support-admin-messages article{max-width:78%;align-self:flex-start}.support-admin-messages article.user{align-self:flex-start}.support-admin-messages article.admin{align-self:flex-end}.support-admin-messages article.ai{align-self:flex-start}.support-admin-messages small{color:var(--text-muted);font-size:10px}.support-admin-messages p{margin:5px 0 0;padding:10px 12px;background:var(--bg-secondary);font-size:13px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}.support-admin-messages article.admin p{background:var(--accent-color);color:var(--text-on-accent)}.support-admin-reply{padding:12px 16px;border-top:1px solid var(--border-color)}.support-admin-reply textarea{width:100%;resize:vertical;border:1px solid var(--border-color);padding:9px;background:var(--bg-primary);color:var(--text-primary);font:inherit;font-size:13px}.support-admin-reply>div{display:flex;justify-content:space-between;align-items:center;margin-top:8px}.support-admin-reply small{color:var(--text-muted)}.support-select-prompt{display:grid;place-items:center;flex:1;color:var(--text-muted);font-size:13px}.support-empty{padding:18px;color:var(--text-muted);font-size:13px}.support-ticket-workbench{padding-top:16px}.support-ticket-toolbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}.support-ticket-toolbar h2,.support-ticket-detail-admin h2{margin:0;font:26px Georgia,serif}.support-ticket-toolbar select{padding:8px 10px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary)}.support-ticket-admin-list .data-panel{overflow:auto}.support-ticket-admin-list table{min-width:850px;width:100%}.support-back{display:block;margin-bottom:16px;border:0;background:none;color:var(--accent-color);font:inherit;font-size:12px;cursor:pointer}.support-ticket-detail-admin{max-width:760px;margin:0 auto;padding:22px;border:1px solid var(--border-color);background:var(--bg-card)}.support-ticket-detail-admin h2{margin:8px 0}.support-ticket-meta{color:var(--text-muted);font-size:12px}.support-ticket-detail-admin article{padding:15px;background:var(--bg-secondary);font-size:14px;line-height:1.65;white-space:pre-wrap;margin:16px 0}.support-ticket-detail-admin textarea{resize:vertical}.support-ticket-detail-admin .support-form-actions{margin-top:14px}@media(max-width:760px){.support-workbench{grid-template-columns:1fr}.support-session-list{max-height:210px;border-right:0;border-bottom:1px solid var(--border-color)}.support-settings form{grid-template-columns:1fr}.support-settings .support-form-actions{grid-column:auto}.support-mode{align-items:flex-start;flex-direction:column}.admin-support-tabs{gap:16px}.support-ticket-detail-admin{padding:16px}}
</style>

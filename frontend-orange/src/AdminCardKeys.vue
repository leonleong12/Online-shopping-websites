<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { formatUtcTime } from './dateTime.js'

const props = defineProps({ product: { type: Object, required: true }, errorText: { type: Function, required: true } })
const emit = defineEmits(['close', 'updated'])
const api = inject('api')
const status = ref(0), items = ref([]), summary = ref({ available: 0, sold: 0 })
const content = ref(''), filterDuplicates = ref(true), busy = ref(false), loading = ref(false)
const message = ref('')

async function load() {
  loading.value = true
  try {
    const data = (await api.get(`/admin/products/${props.product.id}/card-keys`, { params: { status: status.value } })).data
    items.value = data.items
    summary.value = data.summary
  } catch (error) { message.value = props.errorText(error) }
  finally { loading.value = false }
}
async function importKeys() {
  const contents = content.value.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
  if (!contents.length) { message.value = '请先粘贴要导入的卡密'; return }
  busy.value = true
  message.value = ''
  try {
    const result = (await api.post(`/admin/products/${props.product.id}/card-keys`, { contents, filterDuplicates: filterDuplicates.value })).data
    message.value = result.message
    content.value = ''
    emit('updated')
    await load()
  } catch (error) { message.value = props.errorText(error) }
  finally { busy.value = false }
}
async function remove(item) {
  if (!window.confirm('删除这条未售卡密？此操作不可撤销。')) return
  try {
    await api.delete(`/admin/products/${props.product.id}/card-keys/${item.id}`)
    message.value = '卡密已删除'
    emit('updated')
    await load()
  } catch (error) { message.value = props.errorText(error) }
}
watch(status, load)
onMounted(load)
</script>

<template>
  <div class="cardkey-backdrop" @click.self="emit('close')">
    <section class="cardkey-dialog" role="dialog" aria-modal="true" aria-labelledby="cardkey-title">
      <button class="cardkey-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      <span class="cardkey-eyebrow">INVENTORY / DIGITAL DELIVERY</span>
      <h2 id="cardkey-title">{{ product.name }} · 卡密库存</h2>
      <div class="cardkey-stats"><span>可售库存 <strong>{{ summary.available || 0 }}</strong></span><span>已售卡密 <strong>{{ summary.sold || 0 }}</strong></span><span>商品模式 <strong>{{ product.delivery_type === 'AUTO_CARD' ? '自动发货' : '实物发货' }}</strong></span></div>
      <form class="cardkey-import" @submit.prevent="importKeys">
        <label for="cardkey-content">批量导入 <small>一行一条，每次最多 500 条</small></label>
        <textarea id="cardkey-content" v-model="content" rows="5" maxlength="300000" placeholder="激活码 A&#10;账号----密码&#10;网盘链接 提取码"></textarea>
        <label class="cardkey-check"><input v-model="filterDuplicates" type="checkbox"> 自动过滤空行与重复卡密</label>
        <div class="cardkey-actions"><button class="primary" :disabled="busy" type="submit">{{ busy ? '正在导入…' : '确认导入' }}</button><button class="secondary" type="button" @click="load">刷新库存</button></div>
      </form>
      <div class="cardkey-list-head"><h3>卡密明细</h3><div class="cardkey-tabs"><button :class="{ active: status === 0 }" @click="status = 0">未售 {{ summary.available || 0 }}</button><button :class="{ active: status === 1 }" @click="status = 1">已售 {{ summary.sold || 0 }}</button></div></div>
      <p v-if="message" class="cardkey-message" aria-live="polite">{{ message }}</p>
      <div class="cardkey-list">
        <div v-for="item in items" :key="item.id" class="cardkey-row"><code>{{ item.card_content }}</code><div><template v-if="status === 1"><span>订单 #{{ item.order_no }} · {{ item.buyer_username || '用户' }}</span><small>{{ formatUtcTime(item.sold_at) }}</small></template><button v-else class="text-button danger" @click="remove(item)">删除</button></div></div>
        <p v-if="loading" class="cardkey-empty">正在加载…</p><p v-else-if="!items.length" class="cardkey-empty">暂无{{ status === 0 ? '未售' : '已售' }}卡密</p>
      </div>
      <p v-if="product.delivery_type !== 'AUTO_CARD'" class="cardkey-hint">当前仍是实物发货。导入卡密后，请在商品编辑中将发货方式切换为“自动卡密”，顾客付款后才会自动发出。</p>
    </section>
  </div>
</template>

<style scoped>
.cardkey-backdrop{position:fixed;inset:0;z-index:30;display:grid;place-items:center;padding:20px;background:var(--c-29251fb8)}.cardkey-dialog{position:relative;width:min(760px,100%);max-height:min(90vh,900px);overflow:auto;padding:34px;background:var(--bg-card);color:var(--text-primary);border:1px solid var(--border-color);box-shadow:0 18px 60px #29251f33}.cardkey-close{position:absolute;top:13px;right:15px;width:34px;height:34px;background:transparent;color:var(--text-secondary);font-size:24px}.cardkey-eyebrow{font-size:10px;letter-spacing:1.2px;color:var(--text-muted)}.cardkey-dialog h2{margin:10px 40px 22px 0;font:500 26px Georgia,serif}.cardkey-stats{display:flex;gap:24px;padding:14px 0;border-block:1px solid var(--border-color);color:var(--text-secondary);font-size:12px}.cardkey-stats strong{margin-left:6px;color:var(--accent-color);font-size:15px}.cardkey-import{padding:20px 0;border-bottom:1px solid var(--border-color)}.cardkey-import>label:first-child{display:flex;justify-content:space-between;margin-bottom:8px;font-size:12px;font-weight:700}.cardkey-import small,.cardkey-row small{color:var(--text-muted);font-size:10px;font-weight:400}.cardkey-import textarea{width:100%;padding:12px;border:1px solid var(--border-color);background:var(--bg-elevated);color:var(--text-primary);font:12px/1.7 ui-monospace,Consolas,monospace;resize:vertical}.cardkey-check{display:flex;align-items:center;gap:8px;margin:10px 0;color:var(--text-secondary);font-size:11px}.cardkey-check input{accent-color:var(--accent-color)}.cardkey-actions{display:flex;gap:9px}.cardkey-actions button{min-height:38px;padding:0 14px}.cardkey-list-head{display:flex;justify-content:space-between;align-items:center;margin:20px 0 12px}.cardkey-list-head h3{margin:0;font:500 19px Georgia,serif}.cardkey-tabs{display:flex;border-bottom:1px solid var(--border-color)}.cardkey-tabs button{padding:9px 12px;background:transparent;color:var(--text-secondary);font-size:11px}.cardkey-tabs button.active{color:var(--accent-color);border-bottom:2px solid var(--accent-color)}.cardkey-message{padding:10px 12px;background:var(--bg-secondary);color:var(--text-secondary);font-size:12px}.cardkey-list{max-height:240px;overflow:auto;border-top:1px solid var(--border-color)}.cardkey-row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:14px;align-items:center;padding:11px 0;border-bottom:1px solid var(--border-color)}.cardkey-row code{overflow-wrap:anywhere;white-space:pre-wrap;font:11px/1.6 ui-monospace,Consolas,monospace}.cardkey-row>div{display:grid;justify-items:end;gap:3px;color:var(--text-secondary);font-size:10px}.cardkey-empty,.cardkey-hint{color:var(--text-muted);font-size:11px;line-height:1.7}.cardkey-empty{text-align:center;padding:18px}.cardkey-hint{margin:14px 0 0}@media(max-width:600px){.cardkey-dialog{padding:26px 18px}.cardkey-stats{gap:9px;justify-content:space-between;font-size:10px}.cardkey-stats strong{display:block;margin:4px 0 0}.cardkey-list-head{align-items:flex-start;gap:10px;flex-direction:column}}
.cardkey-dialog{min-width:0}.cardkey-row>div{min-width:0;overflow-wrap:anywhere}
@media(max-width:600px){.cardkey-row{grid-template-columns:minmax(0,1fr)}.cardkey-row>div{justify-items:start}}
</style>

<script setup>
import { inject, nextTick, onMounted, ref } from 'vue'

const props = defineProps({ errorText: Function })
const emit = defineEmits(['updated', 'back'])
const api = inject('api')
const categories = ref([]), loading = ref(true), busy = ref(false), error = ref(''), success = ref('')
const editor = ref(null), formError = ref(''), nameInput = ref(null)

async function load() {
  loading.value = true
  try { categories.value = (await api.get('/admin/categories')).data }
  catch (e) { error.value = props.errorText(e) }
  finally { loading.value = false }
}
async function edit(category = null) {
  formError.value = ''
  editor.value = category ? { id: category.id, name: category.name, description: category.description || '', sortOrder: category.sort_order, isVisible: Boolean(category.is_visible) }
    : { id: null, name: '', description: '', sortOrder: Math.min(1000000, Math.max(-10, ...categories.value.map(c => Number(c.sort_order))) + 10), isVisible: true }
  await nextTick()
  nameInput.value?.focus()
}
async function changed(message) {
  success.value = message
  await load()
  emit('updated')
}
async function save() {
  if (busy.value) return
  const data = { name: editor.value.name.trim(), description: editor.value.description.trim(), sortOrder: Number(editor.value.sortOrder), isVisible: editor.value.isVisible }
  if (!data.name || data.name.length > 80) { formError.value = '请填写 1–80 个字符的分类名称'; nameInput.value?.focus(); return }
  if (!Number.isInteger(data.sortOrder) || data.sortOrder < 0 || data.sortOrder > 1000000) { formError.value = '排序数字需为 0–1000000 的整数'; return }
  const id = editor.value.id
  busy.value = true; formError.value = ''; error.value = ''; success.value = ''
  try {
    const result = id ? await api.put(`/admin/categories/${id}`, data) : await api.post('/admin/categories', data)
    editor.value = null
    await changed(result.data.message)
  } catch (e) { formError.value = props.errorText(e) }
  finally { busy.value = false }
}
async function toggle(category) {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api.put(`/admin/categories/${category.id}`, { name: category.name, description: category.description || '', sortOrder: category.sort_order, isVisible: !category.is_visible })
    await changed(category.is_visible ? '已隐藏该分类入口，商品仍可在“所有商品”中购买' : '已启用分类，有上架商品时会显示在前台')
  } catch (e) { error.value = props.errorText(e) }
  finally { busy.value = false }
}
async function move(index, direction) {
  if (busy.value) return
  const next = index + direction
  if (next < 0 || next >= categories.value.length) return
  const ids = categories.value.map(c => c.id)
  ;[ids[index], ids[next]] = [ids[next], ids[index]]
  busy.value = true; error.value = ''; success.value = ''
  try { await api.put('/admin/categories/order', { ids }); await changed('分类顺序已保存') }
  catch (e) { error.value = props.errorText(e); await load() }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <section class="content-page category-admin">
    <div class="page-heading"><div><span class="overline">CATALOG / CATEGORIES</span><h1>分类管理</h1></div><div class="category-heading-actions"><button class="secondary" @click="emit('back')">返回商品管理</button><button class="primary" :disabled="loading || busy" @click="edit()">新增分类 <span>+</span></button></div></div>
    <p class="category-intro">让每一件好物，都有合适的位置。前台只展示已启用且有上架商品的分类。</p>
    <p v-if="error" class="category-notice category-error" role="alert">{{ error }}</p>
    <p v-if="success" class="category-notice" role="status">{{ success }}</p>
    <div class="category-list" :aria-busy="loading || busy">
      <div class="category-list-heading"><span>分类与商品</span><span>前台展示 / 排序</span></div>
      <p v-if="loading" class="category-empty">正在整理分类…</p>
      <p v-else-if="!categories.length" class="category-empty">还没有分类，新增一个，让好物更容易被发现。</p>
      <article v-for="(category, index) in categories" :key="category.id" class="category-row">
        <span class="category-index" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
        <div class="category-copy"><h2>{{ category.name }}</h2><p v-if="category.description">{{ category.description }}</p><small>{{ category.product_count }} 件商品 · {{ category.on_sale_count }} 件上架<span v-if="!category.on_sale_count"> · 前台暂不显示</span></small></div>
        <div class="category-row-actions">
          <button class="category-visibility" :class="{ enabled: category.is_visible }" role="switch" :aria-checked="Boolean(category.is_visible)" :aria-label="`${category.name}前台显示`" :disabled="busy || loading" @click="toggle(category)"><span aria-hidden="true"></span>{{ category.is_visible ? '已启用' : '已隐藏' }}</button>
          <div class="category-order"><button :disabled="index === 0 || busy || loading" :aria-label="`上移${category.name}`" @click="move(index, -1)">↑</button><button :disabled="index === categories.length - 1 || busy || loading" :aria-label="`下移${category.name}`" @click="move(index, 1)">↓</button></div>
          <button class="text-button" :aria-label="`编辑分类${category.name}`" :disabled="busy || loading" @click="edit(category)">编辑</button>
        </div>
      </article>
    </div>
    <p class="category-footnote">使用 ↑ ↓ 调整前台顺序，也可在编辑中填写排序数字（越小越靠前）。隐藏仅收起分类入口，不会下架商品。</p>
    <div v-if="editor" class="modal-backdrop" @click.self="!busy && (editor = null)" @keydown.esc="!busy && (editor = null)">
      <form class="dialog category-dialog" role="dialog" aria-modal="true" aria-labelledby="category-dialog-title" @submit.prevent="save">
        <button class="modal-close" type="button" :disabled="busy" aria-label="关闭分类编辑" @click="editor = null">×</button>
        <span class="overline">CURATE YOUR CATALOG</span><h1 id="category-dialog-title">{{ editor.id ? '编辑分类' : '新增分类' }}</h1>
        <label>分类名称<input ref="nameInput" v-model="editor.name" required maxlength="80" placeholder="例如：游戏储值"></label>
        <label>分类说明（可选）<textarea v-model="editor.description" rows="3" maxlength="255" placeholder="简短介绍这个分类"></textarea></label>
        <label>排序数字<input v-model="editor.sortOrder" type="number" min="0" max="1000000" step="1" required><small>数字越小越靠前，相同数字按创建顺序排列。</small></label>
        <label class="category-checkbox"><input v-model="editor.isVisible" type="checkbox">启用前台分类入口</label>
        <p class="category-footnote">即使启用，没有上架商品时也不会出现在前台。</p>
        <p v-if="formError" class="category-error" role="alert">{{ formError }}</p>
        <div class="category-dialog-actions"><button class="secondary" type="button" :disabled="busy" @click="editor = null">取消</button><button class="primary" type="submit" :disabled="busy">{{ busy ? '正在保存…' : '保存分类' }}</button></div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.category-heading-actions, .category-row-actions, .category-order, .category-dialog-actions { display: flex; align-items: center; gap: 16px; }
.category-intro, .category-footnote { color: var(--text-secondary); font-size: 12px; line-height: 1.8; }
.category-intro { margin: -4px 0 28px; }
.category-list { border: 1px solid var(--border-color); background: var(--bg-card); }
.category-list-heading { display: flex; justify-content: space-between; padding: 16px 28px; color: var(--text-secondary); font-size: 11px; background: var(--bg-elevated); }
.category-row { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; gap: 20px; align-items: center; padding: 24px 28px; border-top: 1px solid var(--border-color); }
.category-index { color: var(--text-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.category-copy h2 { margin: 0; font-size: 17px; font-weight: 500; overflow-wrap: anywhere; }
.category-copy p { margin: 6px 0; font-size: 12px; color: var(--text-secondary); overflow-wrap: anywhere; }
.category-copy small { display: block; margin-top: 6px; color: var(--text-secondary); font-size: 11px; }
.category-row-actions { gap: 24px; }
.category-visibility { display: inline-flex; align-items: center; gap: 8px; min-height: 36px; padding: 0; background: transparent; color: var(--text-secondary); font-size: 12px; white-space: nowrap; }
.category-visibility > span { width: 7px; height: 7px; border-radius: 50%; background: var(--text-muted); }
.category-visibility.enabled { color: var(--accent-color); }
.category-visibility.enabled > span { background: var(--accent-color); }
.category-order { gap: 0; border: 1px solid var(--border-color); }
.category-order button { width: 36px; height: 36px; padding: 0; background: transparent; color: var(--text-primary); }
.category-order button + button { border-left: 1px solid var(--border-color); }
.category-order button:hover:not(:disabled) { background: var(--bg-elevated); color: var(--accent-color); }
.category-order button:disabled { color: var(--text-muted); opacity: .4; }
.category-footnote { margin-top: 18px; }
.category-notice { padding: 12px 16px; border-left: 2px solid var(--accent-color); background: var(--bg-elevated); font-size: 12px; }
.category-error { color: var(--accent-color); font-size: 12px; line-height: 1.7; }
.category-empty { padding: 36px 24px; color: var(--text-secondary); text-align: center; font-size: 13px; }
.category-dialog { width: min(480px, calc(100vw - 32px)); color: var(--text-primary); background: var(--bg-card); }
.category-dialog h1 { font-size: 28px; margin: 10px 0 24px; }
.category-dialog label { display: grid; gap: 8px; margin-top: 18px; }
.category-dialog input, .category-dialog textarea { width: 100%; border: 1px solid var(--border-color); padding: 12px; color: var(--text-primary); background: var(--bg-elevated); border-radius: 0; }
.category-dialog small { font-size: 11px; color: var(--text-secondary); }
.category-dialog .category-checkbox { display: flex; align-items: center; gap: 10px; }
.category-checkbox input { width: 16px; min-height: 16px; height: 16px; accent-color: var(--accent-color); }
.category-dialog-actions { justify-content: flex-end; margin-top: 24px; }
@media (max-width: 680px) {
  .category-heading-actions { gap: 10px; width: 100%; }
  .category-heading-actions button { flex: 1; padding: 10px 12px; }
  .category-row { grid-template-columns: 24px minmax(0, 1fr); gap: 10px; padding: 20px 16px; }
  .category-row-actions { grid-column: 2; justify-content: space-between; gap: 12px; margin-top: 6px; }
  .category-list-heading { padding: 14px 16px; }
  .category-list-heading span:last-child { display: none; }
  .category-dialog { padding: 28px 20px; }
}
</style>

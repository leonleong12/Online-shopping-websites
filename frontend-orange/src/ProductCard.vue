<script setup>
defineProps({ product: Object, quantity: Number, busy: Boolean })
defineEmits(['open', 'add', 'change'])
const money = value => `￥${Number(value || 0).toFixed(2)}`
function fallback(event) {
  if (event.target.dataset.fallbackApplied) return
  event.target.dataset.fallbackApplied = 'true'
  event.target.src = '/product-placeholder.svg'
}
</script>

<template>
  <article class="product-card">
    <button class="image-wrap" @click="$emit('open', product)">
      <img :src="product.image_url || '/product-placeholder.svg'" :alt="product.name" @error="fallback">
      <span v-if="product.is_featured" class="product-tag">编辑推荐</span>
      <span class="quick-view">查看详情 ↗</span>
    </button>
    <div class="product-info">
      <span class="category-label">{{ product.category_name || '精选好物' }}</span>
      <h3><button class="product-title" @click="$emit('open', product)">{{ product.name }}</button></h3>
      <p>{{ product.description || '为日常挑选的耐用品质。' }}</p>
      <span class="product-stock" :class="{ 'out-of-stock': Number(product.stock) === 0 }">{{ Number(product.stock) === 0 ? '暂时缺货' : `库存：${product.stock} 件` }}</span>
      <div class="product-bottom">
        <strong>{{ money(product.price) }}</strong>
        <div v-if="quantity > 0" class="product-quantity" role="group" :aria-label="`${product.name}购物车数量`">
          <button type="button" :disabled="busy" aria-label="减少数量" @click="$emit('change', product, -1)">−</button>
          <span aria-live="polite">{{ quantity }}</span>
          <button type="button" :disabled="busy || quantity >= Number(product.stock)" aria-label="增加数量" @click="$emit('change', product, 1)">+</button>
        </div>
        <button v-else class="add-button" :disabled="busy || Number(product.stock) === 0" @click="$emit('add', product)">{{ Number(product.stock) === 0 ? '暂时缺货' : '加入购物袋' }} <span>+</span></button>
      </div>
    </div>
  </article>
</template>

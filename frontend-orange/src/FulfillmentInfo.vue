<script setup>
import { formatUtcTime } from './dateTime'
defineProps({ order: { type: Object, required: true } })
</script>

<template>
  <section v-if="['SHIPPED', 'COMPLETED'].includes(order.status)" class="fulfillment-info" aria-label="发货信息">
    <div v-if="order.delivery_type === 'PHYSICAL'" class="fulfillment-main">
      <span class="fulfillment-label">快递单号</span><strong class="delivery-value">{{ order.tracking_number }}</strong>
    </div>
    <div v-else-if="order.delivered_cards && !order.virtual_code" class="fulfillment-main">
      <span class="fulfillment-label">卡密已自动发货</span>
    </div>
    <div v-else-if="order.delivery_type === 'VIRTUAL'" class="fulfillment-main fulfillment-main--code">
      <span class="fulfillment-label">虚拟卡密</span><pre class="virtual-code">{{ order.virtual_code }}</pre>
    </div>
    <p v-else class="fulfillment-note">历史订单尚未记录发货信息。</p>
    <small v-if="order.shipped_at">发货时间：{{ formatUtcTime(order.shipped_at) }}</small>
    <small v-if="order.completed_at">收货时间：{{ formatUtcTime(order.completed_at) }}</small>
  </section>
</template>

// Keep a fixed deadline on a monotonic clock, independent of timer frequency
// and differences between the browser and database clocks.
export function createPaymentClock(now = () => performance.now()) {
  let orderId = null
  let expiresAt = 0
  return {
    sync(order) {
      const current = now()
      const deadline = Date.parse(order.payment_deadline_utc)
      const serverNow = Date.parse(order.server_now_utc)
      const remaining = Number.isFinite(deadline) && Number.isFinite(serverNow)
        ? deadline - serverNow
        : Number(order.seconds_remaining) * 1000
      const candidate = current + (order.status === 'PENDING_PAYMENT' && Number.isFinite(remaining) ? Math.max(0, remaining) : 0)
      // A repeated or delayed response must never extend the same order.
      expiresAt = orderId === String(order.id) ? Math.min(expiresAt, candidate) : candidate
      orderId = String(order.id)
    },
    secondsLeft() { return Math.max(0, Math.ceil((expiresAt - now()) / 1000)) }
  }
}

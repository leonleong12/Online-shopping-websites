import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createPaymentClock } from './paymentClock.js'

function fixture() {
  let elapsed = 0
  return { clock: createPaymentClock(() => elapsed), advance: ms => { elapsed += ms } }
}
const pending = (overrides = {}) => ({
  id: 1, status: 'PENDING_PAYMENT',
  server_now_utc: '2026-10-17T08:00:00.000Z',
  payment_deadline_utc: '2026-10-17T08:15:00.000Z',
  ...overrides
})

test('elapsed time includes missed timer callbacks and a background tab', () => {
  const { clock, advance } = fixture()
  clock.sync(pending())
  assert.equal(clock.secondsLeft(), 900)
  advance(735_500)
  assert.equal(clock.secondsLeft(), 165)
  advance(164_500)
  assert.equal(clock.secondsLeft(), 0)
  advance(60_000)
  assert.equal(clock.secondsLeft(), 0)
})

test('server dates determine duration without using the browser wall clock', () => {
  const { clock } = fixture()
  clock.sync(pending({ server_now_utc: '2026-10-17T16:14:30.000+08:00', seconds_remaining: 900 }))
  assert.equal(clock.secondsLeft(), 30)
})

test('a delayed or stale response cannot restart an expired order', () => {
  const { clock, advance } = fixture()
  clock.sync(pending())
  advance(900_000)
  clock.sync(pending())
  assert.equal(clock.secondsLeft(), 0)
  clock.sync(pending({ server_now_utc: undefined, seconds_remaining: 600 }))
  assert.equal(clock.secondsLeft(), 0)
  clock.sync(pending({ id: 2 }))
  assert.equal(clock.secondsLeft(), 900)
})

test('refreshing the same order can shorten but never extend its remaining time', () => {
  const { clock, advance } = fixture()
  clock.sync(pending())
  advance(60_000)
  clock.sync(pending({ server_now_utc: '2026-10-17T08:02:00.000Z' }))
  assert.equal(clock.secondsLeft(), 780)
  clock.sync(pending())
  assert.equal(clock.secondsLeft(), 780)
})

test('cancelled, paid, expired and malformed orders never show a fresh countdown', () => {
  for (const order of [pending({ status: 'CANCELLED' }), pending({ status: 'PAID' }),
    pending({ server_now_utc: '2026-10-17T08:16:00.000Z' }),
    pending({ payment_deadline_utc: null, seconds_remaining: 'invalid' })]) {
    const { clock } = fixture()
    clock.sync(order)
    assert.equal(clock.secondsLeft(), 0)
  }
})

test('older backend responses use remaining seconds without restarting the same order', () => {
  const { clock, advance } = fixture()
  const legacy = pending({ server_now_utc: undefined, seconds_remaining: 5 })
  clock.sync(legacy)
  advance(5_000)
  clock.sync(legacy)
  assert.equal(clock.secondsLeft(), 0)
})

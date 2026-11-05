// Real local HTTP/SQL smoke tests. Requires sqlcmd Windows admin authentication.
// No existing accounts/products are changed. --keep retains QA fixtures for UI testing; --cleanup removes only those fixtures.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'

const base = process.env.WALLET_TEST_BASE || 'http://localhost:8081'
if (!/^http:\/\/localhost:8081$/.test(base)) throw new Error('This smoke test only permits local port 8081')
const host = process.env.WALLET_TEST_DB_HOST || 'localhost\\SQLEXPRESS01'
if (!host.startsWith('localhost\\')) throw new Error('Only local SQL test fixtures are permitted')
const prefix = process.env.WALLET_TEST_PREFIX || `walletui_${randomUUID().slice(0,8)}`
if (!/^walletui_[a-z0-9]{1,12}$/.test(prefix)) throw new Error('Invalid test fixture prefix')
const names = ['admin','buyer','other'].map(s => `${prefix}_${s}`)
const password = 'WalletQa!2026Only'
function sql(query) { return execFileSync('sqlcmd',['-S',host,'-E','-C','-I','-b','-d','online_shop','-h','-1','-W','-Q',`SET NOCOUNT ON; ${query}`],{encoding:'utf8'}).trim() }
const escapedNames = names.map(n => `'${n}'`).join(',')
function cleanup() {
  sql(`SET XACT_ABORT ON; BEGIN TRAN;
    DECLARE @u TABLE(id BIGINT); INSERT INTO @u SELECT id FROM users WHERE username IN (${escapedNames});
    DELETE FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE user_id IN (SELECT id FROM @u));
    DELETE FROM chat_sessions WHERE user_id IN (SELECT id FROM @u);
    DELETE FROM support_tickets WHERE user_id IN (SELECT id FROM @u);
    DELETE FROM card_keys WHERE product_id IN (SELECT id FROM products WHERE name LIKE '${prefix}[_]%') OR buyer_id IN (SELECT id FROM @u);
    DELETE FROM wallet_transactions WHERE user_id IN (SELECT id FROM @u);
    DELETE FROM recharge_codes WHERE batch_key IN (SELECT request_key FROM recharge_batches WHERE created_by IN (SELECT id FROM @u));
    DELETE FROM recharge_batches WHERE created_by IN (SELECT id FROM @u);
    DELETE FROM wallet_redeem_limits WHERE user_id IN (SELECT id FROM @u);
    DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (SELECT id FROM @u));
    DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (SELECT id FROM @u));
    DELETE FROM orders WHERE user_id IN (SELECT id FROM @u);
    DELETE FROM cart_items WHERE user_id IN (SELECT id FROM @u);
    DELETE FROM products WHERE name LIKE '${prefix}[_]%';
    DELETE FROM users WHERE id IN (SELECT id FROM @u); COMMIT;`)
}
if (process.argv.includes('--cleanup')) { cleanup(); console.log('QA fixtures removed:',prefix); process.exit(0) }
assert.equal(Number(sql(`SELECT COUNT(*) FROM users WHERE username IN (${escapedNames})`)),0,'Refusing to overwrite existing fixture names')
let checks = 0
async function request(path,{token,body,method='GET',status=200}={}) {
  const response = await fetch(`${base}/api${path}`,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body === undefined ? {} : {'Content-Type':'application/json'})},...(body === undefined ? {} : {body:JSON.stringify(body)})})
  const data = await response.json()
  assert.equal(response.status,status,`${method} ${path}: ${JSON.stringify(data)}`); checks++
  return {data,headers:response.headers}
}
async function login(name) { return (await request('/auth/login',{method:'POST',body:{username:name,password}})).data.token }
try {
  await request('/wallet',{status:401})
  await request('/admin/wallet/users',{status:401})
  await request('/wallet/redeem',{method:'POST',body:{code:'invalid'},status:401})
  for (const username of names) await request('/auth/register',{method:'POST',body:{username,email:`${username}@example.invalid`,password,role:'ADMIN',balance:'999999'}})
  sql(`UPDATE users SET role='ADMIN' WHERE username='${names[0]}';`)
  const admin = await login(names[0]), buyer = await login(names[1]), other = await login(names[2])
  assert.equal((await request('/wallet',{token:buyer})).data.balance,0)
  await request('/admin/wallet/users',{token:buyer,status:403})
  await request('/admin/wallet/adjust',{token:buyer,method:'POST',body:{username:names[1],amount:'100',remark:'forged',requestId:randomUUID()},status:403})
  await request('/admin/wallet/recharge-codes',{token:buyer,method:'POST',body:{amount:'100',quantity:1,requestId:randomUUID()},status:403})
  await request('/wallet/redeem',{token:buyer,method:'POST',body:{code:'bad',amount:'100'},status:400})
  await request('/admin/wallet/recharge-codes',{token:admin,method:'POST',body:{amount:'10.001',quantity:1,requestId:randomUUID()},status:400})
  await request('/admin/wallet/recharge-codes',{token:admin,method:'POST',body:{amount:'10',quantity:101,requestId:randomUUID()},status:400})
  const batch = {amount:'50.00',quantity:3,requestId:randomUUID()}
  const codes = (await request('/admin/wallet/recharge-codes',{token:admin,method:'POST',body:batch})).data.codes
  const duplicate = (await request('/admin/wallet/recharge-codes',{token:admin,method:'POST',body:batch})).data.codes
  assert.deepEqual(codes,duplicate)
  await request('/wallet/redeem',{token:buyer,method:'POST',body:{code:codes[0].code.toLowerCase()}})
  assert.equal((await request('/wallet',{token:buyer})).data.balance,50)
  await request('/wallet/redeem',{token:buyer,method:'POST',body:{code:codes[0].code},status:400})
  for (let i=0;i<5;i++) await request('/wallet/redeem',{token:other,method:'POST',body:{code:'wrong'},status:i===4?429:400})
  const blocked=await request('/wallet/redeem',{token:other,method:'POST',body:{code:codes[1].code},status:429})
  assert.equal(blocked.headers.get('retry-after'),'60')
  const seenCodes=(await request('/admin/wallet/recharge-codes',{token:admin})).data
  assert.equal(seenCodes.find(c=>c.id===codes[0].id).code,null)
  const adjust={username:names[1],amount:'50.00',remark:'HTTP QA topup',requestId:randomUUID()}
  await request('/admin/wallet/adjust',{token:admin,method:'POST',body:adjust})
  await request('/admin/wallet/adjust',{token:admin,method:'POST',body:adjust})
  assert.equal((await request('/wallet',{token:buyer})).data.balance,100)
  const categoryId=Number(sql('SELECT TOP (1) id FROM categories ORDER BY id'))
  const product=(await request('/admin/products',{token:admin,method:'POST',body:{categoryId,name:`${prefix}_product`,price:80,stock:10,status:'ON_SALE'}})).data.id
  await request('/cart/items',{token:buyer,method:'POST',body:{productId:product,quantity:1,price:0,total:0}})
  const address={recipientName:'钱包测试',recipientPhone:'13800138000',shippingAddress:'仅供本机测试'}
  await request('/orders',{token:buyer,method:'POST',body:{...address,totalAmount:'0'},status:400})
  const order=(await request('/orders',{token:buyer,method:'POST',body:address})).data
  assert.equal(order.totalAmount,80)
  await request(`/orders/${order.orderId}/pay`,{token:other,method:'POST',status:404})
  await request(`/orders/${order.orderId}/pay`,{token:buyer,method:'POST',body:{amount:0},status:400})
  await request('/admin/wallet/adjust',{token:admin,method:'POST',body:{username:names[1],amount:'-80',remark:'HTTP QA insufficient balance',requestId:randomUUID()}})
  await request(`/orders/${order.orderId}/pay`,{token:buyer,method:'POST',status:409})
  assert.equal((await request(`/orders/${order.orderId}`,{token:buyer})).data.order.payment_status,'UNPAID')
  await request('/admin/wallet/adjust',{token:admin,method:'POST',body:{username:names[1],amount:'80',remark:'HTTP QA restore topup',requestId:randomUUID()}})
  await Promise.all(Array.from({length:12},()=>request(`/orders/${order.orderId}/pay`,{token:buyer,method:'POST'})))
  assert.equal((await request('/wallet',{token:buyer})).data.balance,20)
  const ledger=await request('/wallet/transactions',{token:buyer})
  assert.equal(ledger.headers.get('cache-control'),'no-store')
  assert.equal(ledger.data.filter(t=>t.type==='CONSUME').length,1)
  const noOtherData=(await request('/wallet/transactions?userId=1',{token:other})).data
  assert.equal(noOtherData.length,0)
  await request(`/admin/wallet/orders/${order.orderId}/refund`,{token:buyer,method:'POST',body:{remark:'forged'},status:403})
  await Promise.all(Array.from({length:4},()=>request(`/admin/wallet/orders/${order.orderId}/refund`,{token:admin,method:'POST',body:{remark:'HTTP QA refund'}})))
  assert.equal((await request('/wallet',{token:buyer})).data.balance,100)
  assert.equal(Number(sql(`SELECT stock FROM products WHERE id=${product}`)),10)
  await request('/auth/logout',{token:buyer,method:'POST'})
  await request('/wallet',{token:buyer,status:401})
  sql(`UPDATE users SET status='DISABLED' WHERE username='${names[2]}';`)
  await request('/wallet',{token:other,status:403})
  sql(`UPDATE users SET status='ACTIVE' WHERE username='${names[2]}';`)
  console.log(`PASS: ${checks} HTTP checks; prefix=${prefix}; admin=${names[0]}; buyer=${names[1]}`)
} finally {
  if (!process.argv.includes('--keep')) cleanup()
  else console.log(`UI QA fixtures retained. Clean up with WALLET_TEST_PREFIX=${prefix} and --cleanup.`)
}

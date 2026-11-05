// Local-only regression audit. Creates/changes only wallet-smoke QA fixtures.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
const base='http://localhost:8081', prefix='walletui_20261018'
const password='WalletQa!2026Only'
const sql=q=>execFileSync('sqlcmd',['-S','localhost\\SQLEXPRESS01','-E','-C','-I','-b','-d','online_shop','-h','-1','-W','-Q',`SET NOCOUNT ON; ${q}`],{encoding:'utf8'}).trim()
let checks=0
async function req(path,{token,method='GET',body,status=200}={}) {
  const r=await fetch(`${base}/api${path}`,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})})
  const data=await r.json(); assert.equal(r.status,status,`${method} ${path}: ${JSON.stringify(data)}`); checks++;return data
}
const login=async suffix=>(await req('/auth/login',{method:'POST',body:{username:`${prefix}_${suffix}`,password}})).token
const admin=await login('admin'), buyer=await login('buyer'), other=await login('other')
const config=await req('/admin/support/config',{token:admin})
const settings=await req('/site-settings')
const categoryId=(await req('/categories'))[0].id
const address={recipientName:'验收测试',recipientPhone:'13800138000',shippingAddress:'本机隔离测试地址'}
const create=async(name,deliveryType='PHYSICAL')=>(await req('/admin/products',{token:admin,method:'POST',body:{categoryId,name:`${prefix}_${name}`,description:'Isolated regression fixture',price:10,stock:8,status:'ON_SALE',deliveryType}})).id
async function order(productId,quantity=1){ await req('/cart/items',{token:buyer,method:'POST',body:{productId,quantity}});return (await req('/orders',{token:buyer,method:'POST',body:address})).orderId }
try {
  await req('/support/init',{status:401})
  await req('/support/chat/send',{method:'POST',body:{content:'hello'},status:401})
  await req('/admin/support/config',{token:buyer,status:403})
  assert.equal(config.aiModel,'gpt-5.6-luna')
  assert.equal(config.apiKeyConfigured,true)
  assert.equal('aiApiKey' in config,false)
  assert.equal('ai_api_key' in config,false)
  const card=await create('card','AUTO_CARD')
  const imported=await req(`/admin/products/${card}/card-keys`,{token:admin,method:'POST',body:{contents:['AUDIT-KEY-A','AUDIT-KEY-B','AUDIT-KEY-C','AUDIT-KEY-A',''],filterDuplicates:true}})
  assert.equal(imported.inserted,3); assert.equal(imported.stock,3)
  const keys=await req(`/admin/products/${card}/card-keys`,{token:admin})
  const keyC=keys.items.find(k=>k.card_content==='AUDIT-KEY-C')
  await req(`/admin/products/${card}/card-keys/${keyC.id}`,{token:admin,method:'DELETE'})
  await req(`/admin/products/${card}/card-keys`,{token:other,status:403})
  await req(`/admin/products/${card}/featured`,{token:admin,method:'PUT',body:{isFeatured:true}})
  assert((await req('/featured-products')).some(p=>p.id===card))
  assert((await req(`/products?q=${prefix}_card`)).some(p=>p.id===card))
  const cardOrder=await order(card,2)
  await req(`/orders/${cardOrder}`,{token:other,status:404})
  const before=(await req('/wallet',{token:buyer})).balance
  const result=await req(`/orders/${cardOrder}/pay`,{token:buyer,method:'POST'})
  assert(result.message.includes('自动发货'))
  await req(`/orders/${cardOrder}/pay`,{token:buyer,method:'POST'})
  assert.equal((await req('/wallet',{token:buyer})).balance,before-20)
  const delivered=(await req(`/orders/${cardOrder}`,{token:buyer})).order
  assert.equal(delivered.status,'SHIPPED'); assert(delivered.delivered_cards.includes('AUDIT-KEY-A')); assert(delivered.delivered_cards.includes('AUDIT-KEY-B'))
  assert(delivered.shipped_at,'Automatic card delivery must record shipment time'); checks++
  assert.equal((await req(`/products/${card}`)).stock,0)
  const sold=await req(`/admin/products/${card}/card-keys?status=1`,{token:admin})
  assert.equal(sold.items.length,2); assert(sold.items.every(k=>k.buyer_username===`${prefix}_buyer`))
  await req(`/admin/products/${card}/card-keys/${sold.items[0].id}`,{token:admin,method:'DELETE',status:409})
  const reimport=await req(`/admin/products/${card}/card-keys`,{token:admin,method:'POST',body:{contents:['AUDIT-KEY-A'],filterDuplicates:true}})
  assert.equal(reimport.inserted,0,'Must not resell already sold card contents')
  await req(`/orders/${cardOrder}/confirm-receipt`,{token:other,method:'POST',status:404})
  await req(`/orders/${cardOrder}/confirm-receipt`,{token:buyer,method:'POST'})
  assert.equal((await req(`/orders/${cardOrder}`,{token:buyer})).order.status,'COMPLETED')
  const physical=await create('physical'), physicalOrder=await order(physical)
  const physicalPayment=await req(`/orders/${physicalOrder}/pay`,{token:buyer,method:'POST'})
  assert(!physicalPayment.message.includes('卡密'))
  await req(`/admin/orders/${physicalOrder}/ship`,{token:buyer,method:'POST',body:{deliveryType:'PHYSICAL',trackingNumber:'QA123456'},status:403})
  await req(`/admin/orders/${physicalOrder}/ship`,{token:admin,method:'POST',body:{deliveryType:'PHYSICAL',trackingNumber:'QA123456'}})
  assert.equal((await req(`/orders/${physicalOrder}`,{token:buyer})).order.tracking_number,'QA123456')
  await req(`/orders/${physicalOrder}/confirm-receipt`,{token:buyer,method:'POST'})
  const expiryOrder=await order(physical)
  sql(`UPDATE orders SET payment_deadline=DATEADD(second,-1,SYSUTCDATETIME()) WHERE id=${expiryOrder} AND user_id=(SELECT id FROM users WHERE username='${prefix}_buyer');`)
  assert.equal((await req(`/orders/${expiryOrder}`,{token:buyer})).order.status,'CANCELLED')
  await req(`/orders/${expiryOrder}/pay`,{token:buyer,method:'POST',status:409})
  // Upload, save, reload, and fetch the exact persisted image bytes.
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aC1kAAAAASUVORK5CYII=','base64')
  for(const endpoint of ['product-images','site-images']){
    const form=new FormData();form.set('file',new Blob([png],{type:'image/png'}),'audit.png')
    const r=await fetch(`${base}/api/admin/${endpoint}`,{method:'POST',headers:{Authorization:`Bearer ${admin}`},body:form}); assert.equal(r.status,200);checks++
    const {url}=await r.json();const image=await fetch(`${base}${url}`);assert.equal(image.status,200);assert.deepEqual(Buffer.from(await image.arrayBuffer()),png);checks++
    if(endpoint==='product-images'){
      await req(`/admin/products/${physical}`,{token:admin,method:'PUT',body:{categoryId,name:`${prefix}_physical`,price:10,stock:8,status:'ON_SALE',deliveryType:'PHYSICAL',imageUrl:url}})
      assert.equal((await req(`/products/${physical}`)).image_url,url)
    }else{
      await req('/admin/site-settings',{token:admin,method:'PUT',body:{heroImageUrl:url,editorialImageUrl:url}})
      assert.equal((await req('/site-settings')).heroImageUrl,url)
    }
  }
  // Human takeover must never call AI; both sides see messages and unread state.
  await req('/admin/support/config',{token:admin,method:'PUT',body:{...config,adminOnline:true}})
  await req('/support/chat/send',{token:buyer,method:'POST',body:{content:'文'.repeat(151)},status:400})
  const sent=await req('/support/chat/send',{token:buyer,method:'POST',body:{content:'验收测试，请真人回复'}})
  assert.equal(sent.mode,'ADMIN')
  await req('/support/chat/send',{token:buyer,method:'POST',body:{content:'频率测试'},status:429})
  const init=await req('/support/init',{token:buyer})
  const sessionId=init.sessionId
  assert.equal((await req('/admin/support/sessions',{token:admin})).find(s=>s.id===sessionId).unread_for_admin,1)
  await req(`/admin/support/sessions/${sessionId}/messages`,{token:admin})
  await req(`/admin/support/sessions/${sessionId}/messages`,{token:admin,method:'POST',body:{content:'验收回复：您好，已收到'}})
  assert.equal((await req('/support/init',{token:buyer})).unread,1)
  assert((await req('/support/chat/messages',{token:buyer})).some(m=>m.sender_role==='ADMIN'&&m.content.includes('验收回复')))
  assert.equal((await req('/support/init',{token:buyer})).unread,0)
  assert.equal((await req('/support/chat/messages',{token:other})).length,0)
  const ticket=(await req('/support/tickets',{token:buyer,method:'POST',body:{category:'CARD_ISSUE',title:'验收工单',content:'测试订单关联与回复',orderId:cardOrder}})).id
  await req('/support/tickets',{token:other,method:'POST',body:{category:'OTHER',title:'越权测试',content:'不可关联他人订单',orderId:cardOrder},status:400})
  await req(`/support/tickets/${ticket}/close`,{token:other,method:'PUT',status:409})
  await req(`/admin/support/tickets/${ticket}`,{token:admin,method:'PUT',body:{adminReply:'验收处理结果',status:'REPLIED'}})
  assert.equal((await req('/support/tickets',{token:buyer})).find(t=>t.id===ticket).admin_reply,'验收处理结果')
  await req(`/support/tickets/${ticket}/close`,{token:buyer,method:'PUT'})
  assert.equal((await req('/support/tickets',{token:buyer})).find(t=>t.id===ticket).status,'CLOSED')
  // Fill synthetic 20 replies to test the persisted daily AI quota without consuming external tokens.
  const otherSession=(await req('/support/init',{token:other})).sessionId
  sql(`INSERT INTO chat_messages(session_id,user_id,sender_role,content) SELECT ${otherSession},u.id,'AI','QA quota fixture' FROM users u CROSS JOIN (SELECT TOP(20) ROW_NUMBER() OVER(ORDER BY object_id) n FROM sys.objects) n WHERE u.username='${prefix}_other';`)
  await req('/admin/support/config',{token:admin,method:'PUT',body:{...config,adminOnline:false,aiEnabled:true}})
  await req('/support/chat/send',{token:other,method:'POST',body:{content:'额度测试'},status:429})
  console.log(`PASS: ${checks} HTTP checks; auto cards, shipping, receipt, expiry, images, featured/search, human support, tickets, AI quota.`)
}finally{
  await req('/admin/support/config',{token:admin,method:'PUT',body:config})
  await req('/admin/site-settings',{token:admin,method:'PUT',body:settings})
}

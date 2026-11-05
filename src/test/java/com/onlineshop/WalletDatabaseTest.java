package com.onlineshop;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real SQL Server tests; creates isolated fixtures and cleans them with Windows admin sqlcmd. */
@EnabledIfEnvironmentVariable(named="NOVAMART_WALLET_IT",matches="1")
class WalletDatabaseTest {
    static HikariDataSource source;
    static JdbcTemplate db;
    static WalletService wallet;
    static String host, prefix;
    static long admin, product, cardProduct;
    static final List<Long> fixtures=new ArrayList<>();
    static final List<Long> cardProducts=new ArrayList<>();
    static int counter;
    @BeforeAll static void init() throws Exception {
        host=System.getenv().getOrDefault("WALLET_TEST_DB_HOST","localhost\\SQLEXPRESS01");
        if(!host.startsWith("localhost\\")) throw new IllegalStateException("Wallet integration tests only allow a local test SQL instance");
        adminSql("SELECT 1;"); // Check cleanup authority before creating any fixtures.
        HikariConfig config=new HikariConfig();
        config.setJdbcUrl("jdbc:sqlserver://"+host+";databaseName=online_shop;encrypt=false;trustServerCertificate=true");
        config.setUsername(System.getenv().getOrDefault("DB_USER","online_shop_app"));
        config.setPassword(Objects.requireNonNull(System.getenv("DB_PASSWORD"),"Set DB_PASSWORD for opt-in integration tests"));
        config.setMaximumPoolSize(16); config.setConnectionTimeout(15000); config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        source=new HikariDataSource(config); db=new JdbcTemplate(source);
        ProxyFactory factory=new ProxyFactory(new WalletService(db)); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        wallet=(WalletService)factory.getProxy();
        prefix="wqa_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        admin=newUser(); db.update("update users set role='ADMIN' where id=?",admin);
        product=db.queryForObject("insert into products(category_id,name,price,stock,status) output inserted.id values((select top(1) id from categories),?,80,1000,'ON_SALE')",Long.class,prefix);
    }
    static long newUser() {
        String name=prefix+"_"+(++counter);
        long id=db.queryForObject("insert into users(username,password_hash,email) output inserted.id values(?,'TEST-NOT-A-LOGIN',?)",Long.class,name,name+"@example.invalid");
        fixtures.add(id); return id;
    }
    static String username(long user) { return db.queryForObject("select username from users where id=?",String.class,user); }
    static String request() { return UUID.randomUUID().toString(); }
    static BigDecimal balance(long user) { return db.queryForObject("select balance from users where id=?",BigDecimal.class,user); }
    static void credit(long user,String value) { wallet.adjust(admin,username(user),value,"Test fixture credit",request()); }
    static long order(long user,String amount) {
        long id=db.queryForObject("insert into orders(user_id,total_amount,recipient_name,recipient_phone,shipping_address,payment_deadline) output inserted.id values(?,?,N'Test','13800138000',N'Test only',DATEADD(minute,15,SYSUTCDATETIME()))",Long.class,user,new BigDecimal(amount));
        db.update("insert into order_items(order_id,product_id,product_name,unit_price,quantity) values(?,?,?, ?,1)",id,product,prefix,new BigDecimal(amount));
        db.update("update products set stock=stock-1 where id=?",product);
        return id;
    }
    static long cardOrder(long user,int quantity) {
        BigDecimal total=BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity));
        long id=db.queryForObject("insert into orders(user_id,total_amount,recipient_name,recipient_phone,shipping_address,payment_deadline) output inserted.id values(?,?,N'Test','13800138000',N'Test only',DATEADD(minute,15,SYSUTCDATETIME()))",Long.class,user,total);
        db.update("insert into order_items(order_id,product_id,product_name,unit_price,quantity) values(?,?,?,10,?)",id,cardProduct,prefix+"_cards",quantity);
        db.update("update products set stock=stock-? where id=?",quantity,cardProduct);
        return id;
    }
    static long newCardProduct() {
        long id=db.queryForObject("insert into products(category_id,name,price,stock,status,delivery_type) output inserted.id values((select top(1) id from categories),?,10,0,'ON_SALE','AUTO_CARD')",Long.class,prefix+"_cards_"+(cardProducts.size()+1));
        cardProducts.add(id);
        return id;
    }
    static void addCardKeys(int count) {
        for(int i=0;i<count;i++) db.update("insert into card_keys(product_id,card_content) values(?,?)",cardProduct,prefix+"-KEY-"+(i+1));
        db.update("update products set stock=(select count(*) from card_keys where product_id=? and status=0) - (select coalesce(sum(oi.quantity),0) from order_items oi join orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') where id=?",cardProduct,cardProduct,cardProduct);
    }
    @SuppressWarnings("unchecked") static String code(String amount) {
        var result=wallet.generate(admin,amount,1,request());
        return String.valueOf(((List<Map<String,Object>>)result.get("codes")).getFirst().get("code"));
    }
    static int count(String sql,Object...params) { return db.queryForObject(sql,Integer.class,params); }
    static void error(int status,Runnable operation) { assertEquals(status,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value()); }
    static List<Object> race(int size,IntFunction<Object> action) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(size); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Object>> work=new ArrayList<>();
            for(int i=0;i<size;i++) { final int index=i; work.add(pool.submit(()->{ start.await(); try { return action.apply(index); } catch(Exception e) { return e; } })); }
            start.countDown(); List<Object> results=new ArrayList<>(); for(var task:work) results.add(task.get(40,TimeUnit.SECONDS)); return results;
        } finally { pool.shutdownNow(); }
    }
    @Test void nonnegativeBalanceAndAppendOnlyLedger() {
        long user=newUser(); credit(user,"10");
        assertThrows(DataAccessException.class,()->db.update("update users set balance=-1 where id=?",user));
        assertThrows(DataAccessException.class,()->db.update("delete from wallet_transactions where user_id=?",user));
        assertThrows(DataAccessException.class,()->db.update("update wallet_transactions set amount=1 where user_id=?",user));
        assertEquals(new BigDecimal("10.00"),balance(user));
    }
    @Test void sameCodeOnlyCreditsOneOfTwoUsers() throws Exception {
        long a=newUser(),b=newUser(); String code=code("50");
        var results=race(2,i->wallet.redeem(i==0?a:b,code));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        assertEquals(1,results.stream().filter(x->!((Map<?,?>)x).containsKey("error")).count());
        assertEquals(new BigDecimal("50.00"),balance(a).add(balance(b)));
        assertEquals(1,count("select count(*) from wallet_transactions where user_id in (?,?) and type='RECHARGE'",a,b));
    }
    @Test void duplicateRedemptionFromOneUserOnlyCreditsOnce() throws Exception {
        long user=newUser(); String code=code("10"); var results=race(2,i->wallet.redeem(user,code));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        assertEquals(1,results.stream().filter(x->!((Map<?,?>)x).containsKey("error")).count());
        assertEquals(new BigDecimal("10.00"),balance(user));
    }
    @Test void fiveFailuresPersistCooldownAndThenAllowValidCode() {
        long user=newUser(); String code=code("10");
        for(int i=1;i<=5;i++) assertEquals(i==5?429:400,wallet.redeem(user,"invalid").get("status"));
        assertEquals(429,wallet.redeem(user,code).get("status")); assertEquals(BigDecimal.ZERO.setScale(2),balance(user));
        assertEquals(0,count("select status from recharge_codes where code=?",code));
        db.update("update wallet_redeem_limits set blocked_until=DATEADD(second,-1,SYSUTCDATETIME()) where user_id=?",user);
        assertFalse(wallet.redeem(user,code).containsKey("error"));
        assertEquals(0,count("select failures from wallet_redeem_limits where user_id=?",user));
    }
    @Test void insufficientBalanceRollsBackOrderAndLedger() {
        long user=newUser(); credit(user,"10"); long id=order(user,"80");
        error(409,()->wallet.pay(id,user));
        assertEquals("PENDING_PAYMENT",db.queryForObject("select status from orders where id=?",String.class,id));
        assertEquals("UNPAID",db.queryForObject("select payment_status from orders where id=?",String.class,id));
        assertEquals(new BigDecimal("10.00"),balance(user)); assertEquals(0,count("select count(*) from payments where order_id=?",id));
        assertEquals(0,count("select count(*) from wallet_transactions where related_order_no=?",String.valueOf(id)));
    }
    @Test void insufficientCardInventoryRollsBackWalletPayment() {
        cardProduct=newCardProduct(); long user=newUser(); credit(user,"100"); addCardKeys(2); long id=cardOrder(user,2);
        // Simulate inventory corruption after a valid reservation, not an impossible negative-stock order.
        db.update("delete from card_keys where product_id=? and card_content=?",cardProduct,prefix+"-KEY-2");
        error(409,()->wallet.pay(id,user));
        assertEquals(new BigDecimal("100.00"),balance(user));
        assertEquals("UNPAID",db.queryForObject("select payment_status from orders where id=?",String.class,id));
        assertEquals(0,count("select count(*) from payments where order_id=?",id));
        assertEquals(0,count("select count(*) from card_keys where product_id=? and status=1",cardProduct));
    }
    @Test void successfulCardPaymentDebitsAndDeliversExactlyOnce() {
        cardProduct=newCardProduct(); long user=newUser(); credit(user,"100"); addCardKeys(3); long id=cardOrder(user,2);
        var result=wallet.pay(id,user);
        assertTrue(String.valueOf(result.get("message")).contains("自动发货"));
        assertEquals(new BigDecimal("80.00"),balance(user));
        assertEquals("SHIPPED",db.queryForObject("select status from orders where id=?",String.class,id));
        assertEquals("PAID",db.queryForObject("select payment_status from orders where id=?",String.class,id));
        assertEquals(2,count("select count(*) from card_keys where product_id=? and order_no=? and buyer_id=? and status=1 and sold_at is not null",cardProduct,id,user));
        String delivered=db.queryForObject("select delivered_cards from orders where id=?",String.class,id);
        assertTrue(delivered.contains(prefix+"-KEY-1")); assertTrue(delivered.contains(prefix+"-KEY-2"));
        assertEquals(1,count("select stock from products where id=?",cardProduct));
        wallet.pay(id,user);
        assertEquals(new BigDecimal("80.00"),balance(user));
        assertEquals(2,count("select count(*) from card_keys where product_id=? and order_no=?",cardProduct,id));
    }
    @Test void repeatedParallelPaymentsDebitOnce() throws Exception {
        long user=newUser(); credit(user,"100"); long id=order(user,"80"); var results=race(8,i->wallet.pay(id,user));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        assertEquals(new BigDecimal("20.00"),balance(user));
        assertEquals(1,count("select count(*) from wallet_transactions where related_order_no=? and type='CONSUME'",String.valueOf(id)));
        assertEquals(1,count("select count(*) from payments where order_id=? and payment_method='WALLET'",id));
    }
    @Test void twoOrdersCannotOverspendOneBalance() throws Exception {
        long user=newUser(); credit(user,"100"); long a=order(user,"80"),b=order(user,"80");
        var results=race(2,i->wallet.pay(i==0?a:b,user));
        assertEquals(1,results.stream().filter(Map.class::isInstance).count(),results.toString());
        assertEquals(1,results.stream().filter(ResponseStatusException.class::isInstance).count(),results.toString());
        assertEquals(new BigDecimal("20.00"),balance(user));
        assertEquals(1,count("select count(*) from orders where id in (?,?) and payment_status='PAID'",a,b));
    }
    @Test void failedLedgerInsertRollsBackDebitAndPaymentStatus() {
        long user=newUser(); credit(user,"100"); long id=order(user,"80");
        db.update("insert into wallet_transactions(user_id,type,amount,balance_after,related_order_no,remark) values(?,'CONSUME',0,100,?,'Test rollback conflict')",user,String.valueOf(id));
        assertThrows(DataAccessException.class,()->wallet.pay(id,user));
        assertEquals(new BigDecimal("100.00"),balance(user));
        assertEquals("UNPAID",db.queryForObject("select payment_status from orders where id=?",String.class,id));
        assertEquals(0,count("select count(*) from payments where order_id=?",id));
    }
    @Test void failedLedgerInsertAlsoRollsBackCodeUsageAndCredit() {
        long user=newUser(); String code=code("10"); long id=db.queryForObject("select id from recharge_codes where code=?",Long.class,code);
        db.update("insert into wallet_transactions(user_id,type,amount,balance_after,remark,request_key) values(?,'RECHARGE',1,0,'Test rollback conflict',?)",user,"REDEEM:"+id);
        assertThrows(DataAccessException.class,()->wallet.redeem(user,code));
        assertEquals(new BigDecimal("0.00"),balance(user));
        assertEquals(0,count("select status from recharge_codes where id=?",id));
        assertNull(db.queryForObject("select used_by from recharge_codes where id=?",Long.class,id));
    }
    @Test void adjustmentIsIdempotentAndCannotOverdraw() throws Exception {
        long user=newUser(); String key=request();
        var results=race(6,i->wallet.adjust(admin,username(user),"50","Test idempotency",key));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        assertEquals(new BigDecimal("50.00"),balance(user));
        assertEquals(1,count("select count(*) from wallet_transactions where user_id=?",user));
        error(409,()->wallet.adjust(admin,username(user),"60","Test idempotency",key));
        error(409,()->wallet.adjust(admin,username(user),"-51","Test overdraw",request()));
        assertEquals(new BigDecimal("50.00"),balance(user));
    }
    @Test void concurrentAdjustmentsHaveAccurateBalanceSnapshots() throws Exception {
        long user=newUser(); var results=race(12,i->wallet.adjust(admin,username(user),"0.01","Test decimal increment",request()));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        assertEquals(new BigDecimal("0.12"),balance(user));
        var snapshots=db.queryForList("select balance_after from wallet_transactions where user_id=? order by id",BigDecimal.class,user);
        for(int i=0;i<snapshots.size();i++) assertEquals(BigDecimal.valueOf(i+1,2),snapshots.get(i));
    }
    @Test void batchGenerationIsIdempotentAndCodesHave128BitsOfEntropy() throws Exception {
        String key=request(); var results=race(4,i->wallet.generate(admin,"50",10,key));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        var codes=db.queryForList("select code from recharge_codes where batch_key=?",String.class,"BATCH:"+admin+":"+key);
        assertEquals(10,codes.size()); assertEquals(10,new HashSet<>(codes).size());
        assertTrue(codes.stream().allMatch(c->c.matches("NV(?:-[A-F0-9]{8}){4}")));
        error(409,()->wallet.generate(admin,"10",10,key));
        error(400,()->wallet.generate(admin,"10",101,request()));
    }
    @SuppressWarnings("unchecked")
    @Test void rechargeCodePagesKeepUnusedFirstAndUsedByRedemptionTime() {
        var all=new ArrayList<Map<String,Object>>();
        all.addAll((List<Map<String,Object>>)wallet.generate(admin,"12.34",100,request()).get("codes"));
        all.addAll((List<Map<String,Object>>)wallet.generate(admin,"12.34",5,request()).get("codes"));
        String[] dates={"2099-01-01T00:00:00.1234567","2099-01-01T00:00:00.1234568","2098-01-01T00:00:00","2099-01-01T00:00:00.1234568","2097-01-01T00:00:00"};
        for(int i=0;i<dates.length;i++) db.update("update recharge_codes set status=1,used_by=?,used_at=convert(datetime2,?) where id=?",admin,dates[i],all.get(100+i).get("id"));
        SessionAuth auth=org.mockito.Mockito.mock(SessionAuth.class);
        org.mockito.Mockito.when(auth.admin("Bearer test")).thenReturn(admin);
        WalletController controller=new WalletController(auth,wallet,db);
        var fixtureIds=new HashSet<Long>();
        for(var code:all) fixtureIds.add(((Number)code.get("id")).longValue());
        var seen=new HashSet<Long>(); var actual=new ArrayList<Long>();
        long before=Long.MAX_VALUE; boolean reachedUsed=false;
        for(int page=0;;page++) {
            assertTrue(page<1000,"Pagination did not terminate");
            var rows=controller.codes("Bearer test",before);
            if(rows.isEmpty()) break;
            for(var row:rows) {
                long id=((Number)row.get("id")).longValue();
                assertTrue(seen.add(id),"Duplicate code across pages");
                if(((Number)row.get("status")).intValue()==1) { reachedUsed=true; assertNull(row.get("code")); }
                else assertFalse(reachedUsed,"Unused code appeared after used codes");
                if(fixtureIds.contains(id)) actual.add(id);
            }
            before=((Number)rows.getLast().get("id")).longValue();
        }
        var expected=new ArrayList<Long>();
        for(int i=99;i>=0;i--) expected.add(((Number)all.get(i).get("id")).longValue());
        for(int i:new int[]{103,101,100,102,104}) expected.add(((Number)all.get(i).get("id")).longValue());
        assertEquals(expected,actual,"Codes were skipped or sorted by creation rather than redemption");
    }
    @Test void refundIsIdempotentAndRestoresStockOnce() throws Exception {
        long user=newUser(); credit(user,"100"); long id=order(user,"80"); wallet.pay(id,user);
        int before=count("select stock from products where id=?",product);
        var results=race(4,i->wallet.refund(admin,id,"Test refund"));
        assertTrue(results.stream().noneMatch(Throwable.class::isInstance),results.toString());
        assertEquals(new BigDecimal("100.00"),balance(user));
        assertEquals(before+1,count("select stock from products where id=?",product));
        assertEquals(1,count("select count(*) from wallet_transactions where related_order_no=? and type='REFUND'",String.valueOf(id)));
        assertEquals("REFUNDED",db.queryForObject("select payment_status from orders where id=?",String.class,id));
    }
    @Test void cannotPayOthersOrdersExpiredOrdersOrMismatchedAmounts() {
        long user=newUser(),other=newUser(); credit(user,"100"); long id=order(user,"80");
        error(404,()->wallet.pay(id,other));
        db.update("update orders set total_amount=1 where id=?",id); error(409,()->wallet.pay(id,user));
        db.update("update orders set total_amount=80,payment_deadline=DATEADD(second,-1,SYSUTCDATETIME()) where id=?",id); error(409,()->wallet.pay(id,user));
        assertEquals(new BigDecimal("100.00"),balance(user));
    }
    @Test void balanceUpperLimitPreservesUnredeemedCode() {
        long user=newUser(); credit(user,"99999999.99"); String code=code("10");
        error(409,()->wallet.redeem(user,code));
        assertEquals(0,count("select status from recharge_codes where code=?",code));
        error(409,()->wallet.adjust(admin,username(user),"0.01","Test limit",request()));
        assertEquals(new BigDecimal("99999999.99"),balance(user));
    }
    static void adminSql(String sql) throws Exception {
        Process process=new ProcessBuilder("sqlcmd","-S",host,"-E","-C","-I","-b","-d","online_shop","-Q",sql).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        if(!process.waitFor(30,TimeUnit.SECONDS) || process.exitValue()!=0) throw new IllegalStateException("Test fixture administration failed: "+output);
    }
    @AfterAll static void cleanup() throws Exception {
        try {
            if(!fixtures.isEmpty()) {
                String ids=String.join(",",fixtures.stream().map(String::valueOf).toList());
                String cardIds=String.join(",",cardProducts.stream().map(String::valueOf).toList());
                if(cardIds.isEmpty()) cardIds="-1";
                adminSql("SET XACT_ABORT ON; BEGIN TRAN; DELETE FROM wallet_transactions WHERE user_id IN ("+ids+"); DELETE FROM recharge_codes WHERE batch_key IN (SELECT request_key FROM recharge_batches WHERE created_by="+admin+"); DELETE FROM recharge_batches WHERE created_by="+admin+"; DELETE FROM wallet_redeem_limits WHERE user_id IN ("+ids+"); DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE user_id IN ("+ids+")); DELETE FROM card_keys WHERE product_id IN ("+cardIds+"); DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN ("+ids+")); DELETE FROM orders WHERE user_id IN ("+ids+"); DELETE FROM products WHERE id IN ("+product+","+cardIds+"); DELETE FROM users WHERE id IN ("+ids+"); COMMIT;");
            }
        } finally { if(source!=null) source.close(); }
    }
}

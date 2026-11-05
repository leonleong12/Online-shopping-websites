package com.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportControllerTest {
    JdbcTemplate db;
    SessionAuth auth;
    HttpClient http;
    SupportController controller;
    Map<String,Object> config;
    @BeforeEach void setup() {
        db=mock(JdbcTemplate.class); auth=mock(SessionAuth.class); http=mock(HttpClient.class);
        controller=new SupportController(db,auth,new ObjectMapper());
        ReflectionTestUtils.setField(controller,"http",http);
        config=new HashMap<>(Map.of("admin_online",false,"ai_enabled",true,
            "ai_base_url","https://api.aijws.com/v1","ai_model","stored-model",
            "ai_api_key","test-key-not-real","welcome_message","Welcome"));
        when(db.queryForMap("select * from support_config where id=1")).thenReturn(config);
        when(auth.admin(any())).thenReturn(1L); when(auth.user(any())).thenReturn(2L);
    }
    @Test void configNeverReturnsSecret() {
        var result=controller.adminConfig("Bearer test");
        assertEquals(true,result.get("apiKeyConfigured"));
        assertFalse(result.toString().contains("test-key-not-real"));
        assertFalse(result.containsKey("aiApiKey"));
        assertEquals("RESPONSES",result.get("aiProtocol"));
    }
    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(strings={"CHAT_COMPLETIONS","ANTHROPIC_MESSAGES"})
    void protocolControlsEndpointAuthenticationPayloadAndTextParsing(String protocol) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(protocol.equals("CHAT_COMPLETIONS")
            ? "{\"choices\":[{\"message\":{\"content\":\"聊天连接成功\"}}]}"
            : "{\"type\":\"message\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"private\"},{\"type\":\"text\",\"text\":\"消息连接成功\"}]}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var result=controller.testAi("Bearer test",Map.of("aiProtocol",protocol,"aiBaseUrl","https://api.aijws.com/v1/responses","aiModel","custom-model"));
        assertEquals(protocol.equals("CHAT_COMPLETIONS")?"聊天连接成功":"消息连接成功",result.get("reply"));
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var actual=request.getValue();
        var payload=new ObjectMapper().readTree(requestBody(actual));
        assertEquals("custom-model",payload.path("model").asText());
        assertFalse(payload.has("input"));
        assertFalse(payload.has("instructions"));
        assertFalse(payload.has("temperature"));
        if(protocol.equals("CHAT_COMPLETIONS")) {
            assertEquals("https://api.aijws.com/v1/chat/completions",actual.uri().toString());
            assertEquals("Bearer test-key-not-real",actual.headers().firstValue("Authorization").orElseThrow());
            assertTrue(actual.headers().firstValue("x-api-key").isEmpty());
            assertEquals("system",payload.path("messages").get(0).path("role").asText());
            assertEquals(1024,payload.path("max_completion_tokens").asInt());
        } else {
            assertEquals("https://api.aijws.com/v1/messages",actual.uri().toString());
            assertEquals("test-key-not-real",actual.headers().firstValue("x-api-key").orElseThrow());
            assertEquals("2023-06-01",actual.headers().firstValue("anthropic-version").orElseThrow());
            assertTrue(actual.headers().firstValue("Authorization").isEmpty());
            assertEquals("user",payload.path("messages").get(0).path("role").asText());
            assertEquals(1,payload.path("messages").size());
            assertTrue(payload.path("system").asText().contains("NovaMart"));
            assertEquals(1024,payload.path("max_tokens").asInt());
        }
        assertFalse(config.containsKey("ai_protocol"),"Testing unsaved protocol must not persist it");
    }
    @ParameterizedTest
    @ValueSource(strings={"CHAT_COMPLETIONS","RESPONSES","ANTHROPIC_MESSAGES"})
    void protocolIsPersistedAndReturnedInAdminConfig(String protocol) {
        controller.updateConfig("Bearer test",Map.of("adminOnline",false,"aiEnabled",true,"aiProtocol",protocol));
        verify(db).update(contains("ai_protocol=coalesce(?,ai_protocol)"),eq(false),eq(true),isNull(),isNull(),isNull(),isNull(),isNull(),eq(protocol));
        config.put("ai_protocol",protocol);
        assertEquals(protocol,controller.adminConfig("Bearer test").get("aiProtocol"));
    }
    @Test void unknownProtocolIsRejectedBeforeNetworkOrWrite() {
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.updateConfig("Bearer test",Map.of("adminOnline",false,"aiEnabled",true,"aiProtocol","INVALID"))).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.testAi("Bearer test",Map.of("aiProtocol","INVALID"))).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.availableModels("Bearer test",Map.of("aiProtocol","INVALID"))).getStatusCode().value());
        verifyNoInteractions(http);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @SuppressWarnings("unchecked")
    @Test void modelsUseUnsavedAddressKeyAndReturnSortedUniqueIdsWithoutChangingModel() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"z-model\"},{\"id\":\"\"}]}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var result=controller.availableModels("Bearer test",Map.of("aiBaseUrl","https://api.aijws.com/v1/chat/completions","aiApiKey","temporary-key","aiModel",""));
        assertEquals(List.of(Map.of("id","a-model","name","a-model"),Map.of("id","z-model","name","z-model")),result.get("models"));
        assertFalse(result.toString().contains("temporary-key"));
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("https://api.aijws.com/v1/models",request.getValue().uri().toString());
        assertEquals("GET",request.getValue().method());
        assertEquals("Bearer temporary-key",request.getValue().headers().firstValue("Authorization").orElseThrow());
        assertEquals("stored-model",config.get("ai_model"));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @SuppressWarnings("unchecked")
    @Test void anthropicModelListingUsesVersionHeaderAndFollowsPagination() throws Exception {
        HttpResponse<String> first=mock(HttpResponse.class),second=mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200);when(second.statusCode()).thenReturn(200);
        when(first.body()).thenReturn("{\"data\":[{\"id\":\"claude-a\",\"display_name\":\"Claude A\"}],\"has_more\":true,\"last_id\":\"claude-a\"}");
        when(second.body()).thenReturn("{\"data\":[{\"id\":\"claude-b\"}],\"has_more\":false}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(first,second);
        var result=controller.availableModels("Bearer test",Map.of("aiProtocol","ANTHROPIC_MESSAGES","aiBaseUrl","https://api.aijws.com/v1/messages"));
        assertEquals(2,((List<?>)result.get("models")).size());
        ArgumentCaptor<HttpRequest> requests=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(2)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("https://api.aijws.com/v1/models?limit=1000&after_id=claude-a",requests.getAllValues().get(1).uri().toString());
        for(var request:requests.getAllValues()) {
            assertEquals("test-key-not-real",request.headers().firstValue("x-api-key").orElseThrow());
            assertEquals("2023-06-01",request.headers().firstValue("anthropic-version").orElseThrow());
        }
    }
    @SuppressWarnings("unchecked")
    @Test void missingModelListingExplainsManualEntryWithoutLeakingSupplierBody() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);when(response.body()).thenReturn("secret supplier body");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var error=assertThrows(ResponseStatusException.class,()->controller.availableModels("Bearer test",null));
        assertEquals(502,error.getStatusCode().value());
        assertTrue(error.getReason().contains("手动填写"));
        assertFalse(error.toString().contains("secret supplier body"));
    }
    @SuppressWarnings("unchecked")
    @Test void emptyAndMalformedModelListsAreHandledExplicitly() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.body()).thenReturn("{\"data\":[]}");
        assertTrue(((List<?>)controller.availableModels("Bearer test",null).get("models")).isEmpty());
        when(response.body()).thenReturn("{\"error\":{\"message\":\"private error\"}}");
        assertEquals(502,assertThrows(ResponseStatusException.class,()->controller.availableModels("Bearer test",null)).getStatusCode().value());
    }
    @Test void modelFetchingRequiresAdminAndConfiguredKey() {
        config.put("ai_api_key","");
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.availableModels("Bearer test",null)).getStatusCode().value());
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(auth).admin("Bearer buyer");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.availableModels("Bearer buyer",null)).getStatusCode().value());
        verifyNoInteractions(http);
    }
    @SuppressWarnings("unchecked")
    @Test void testUsesUnsavedTextModelAndKeyWithoutPersisting() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[]},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"连接测试成功\"}]}]}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var result=controller.testAi("Bearer test",Map.of("aiModel","custom-text-model","aiApiKey","temporary-key"));
        assertEquals("连接测试成功",result.get("reply"));
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("Bearer temporary-key",request.getValue().headers().firstValue("Authorization").orElseThrow());
        assertEquals("https://api.aijws.com/v1/responses",request.getValue().uri().toString());
        var payload=new ObjectMapper().readTree(requestBody(request.getValue()));
        assertEquals("custom-text-model",payload.path("model").asText());
        assertTrue(payload.path("instructions").asText().contains("连接测试成功"));
        assertEquals("user",payload.path("input").get(0).path("role").asText());
        assertEquals(1,payload.path("input").size());
        assertEquals(1024,payload.path("max_output_tokens").asInt());
        assertFalse(payload.path("store").asBoolean());
        assertFalse(payload.has("messages"));
        assertFalse(payload.has("max_tokens"));
        assertFalse(payload.has("temperature"));
        assertEquals("stored-model",config.get("ai_model"));
        assertEquals("test-key-not-real",config.get("ai_api_key"));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @SuppressWarnings("unchecked")
    @Test void responsesEndpointAcceptsBaseOrFullAddressWithoutDuplicatingPath() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"output_text\":\"连接成功\"}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        for(String address:List.of("https://api.aijws.com/v1/", "https://api.aijws.com/v1/responses/", "https://api.aijws.com/v1/chat/completions", "https://api.aijws.com")) {
            assertEquals("连接成功",controller.testAi("Bearer test",Map.of("aiBaseUrl",address)).get("reply"));
        }
        ArgumentCaptor<HttpRequest> requests=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(4)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
        assertTrue(requests.getAllValues().stream().allMatch(r->r.uri().toString().equals("https://api.aijws.com/v1/responses")));
    }
    @SuppressWarnings("unchecked")
    @Test void responsesConcatenatesOnlyAssistantOutputAndLimitsUnicodeSafely() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ObjectMapper().writeValueAsString(Map.of("output",List.of(
            Map.of("type","reasoning","text","private reasoning"),
            Map.of("type","message","role","assistant","content",List.of(
                Map.of("type","output_text","text","您好"),
                Map.of("type","output_text","text","😀".repeat(520))))))));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        String reply=(String)controller.testAi("Bearer test",null).get("reply");
        assertTrue(reply.startsWith("您好😀"));
        assertEquals(400,reply.codePointCount(0,reply.length()));
        assertTrue(reply.endsWith("…"));
        assertTrue(reply.length() <= 1000,"Reply must fit chat_messages NVARCHAR(1000)");
        assertFalse(reply.contains("private reasoning"));
        assertFalse(Character.isHighSurrogate(reply.charAt(reply.length()-1)));
    }
    @SuppressWarnings("unchecked")
    @Test void emptyFailedAndReasoningOnlyResponsesAreNotReportedAsSuccess() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        for(String body:List.of("{\"status\":\"incomplete\",\"output\":[{\"type\":\"reasoning\"}]}",
                "{\"status\":\"failed\",\"error\":{\"message\":\"private supplier detail\"}}", "{\"output\":[]}")) {
            when(response.body()).thenReturn(body);
            var error=assertThrows(ResponseStatusException.class,()->controller.testAi("Bearer test",null));
            assertEquals(502,error.getStatusCode().value());
            assertFalse(error.getReason().contains("private supplier detail"));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void buyerChatUsesResponsesAndSavesReply() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"这款商品有现货\"}]}]}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(db.queryForList("select id from chat_sessions where user_id=?",2L)).thenReturn(List.of(Map.of("id",8L)));
        when(db.queryForList(contains("select top 12 sender_role"),eq(8L))).thenReturn(List.of(Map.of("sender_role","USER","content","有哪些现货？")));
        assertEquals("AI",controller.send("Bearer test",Map.of("content","有哪些现货？")).get("mode"));
        verify(db).update("insert into chat_messages(session_id,user_id,sender_role,content) values(?,?,?,?)",8L,2L,"AI","这款商品有现货");
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var payload=new ObjectMapper().readTree(requestBody(request.getValue()));
        assertTrue(payload.path("instructions").asText().contains("当前店内在售商品实时数据"));
        assertEquals("有哪些现货？",payload.path("input").get(0).path("content").asText());
    }
    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(strings={"CHAT_COMPLETIONS","RESPONSES","ANTHROPIC_MESSAGES"})
    void shoppingContextAndLongerCompleteReplyWorkAcrossProtocols(String protocol) throws Exception {
        config.put("ai_protocol",protocol);
        String reply="先打开顶部的我的钱包，输入储值码并点击立即兑换，到账后可查看收支明细。".repeat(5);
        var mapper=new ObjectMapper();
        Object result=switch(protocol) {
            case "CHAT_COMPLETIONS" -> Map.of("choices",List.of(Map.of("message",Map.of("content",reply))));
            case "ANTHROPIC_MESSAGES" -> Map.of("content",List.of(Map.of("type","text","text",reply)));
            default -> Map.of("output_text",reply);
        };
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(mapper.writeValueAsString(result));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(db.queryForList("select id from chat_sessions where user_id=?",2L)).thenReturn(List.of(Map.of("id",8L)));
        when(db.queryForList(contains("from products where status='ON_SALE'"))).thenReturn(List.of(Map.of(
            "name","旅行杯","price",new java.math.BigDecimal("79.00"),"stock",8,"description","300ml",
            "delivery_type","PHYSICAL","is_featured",true)));
        when(db.queryForList(contains("select top 3 id,status"),eq(2L))).thenReturn(List.of(Map.of(
            "id",41L,"status","PENDING_PAYMENT","payment_status","UNPAID","total_amount",79)));
        when(db.queryForList(contains("from order_items oi join orders"),eq(41L),eq(2L))).thenReturn(List.of(Map.of(
            "product_name","旅行杯","unit_price",79,"quantity",1)));
        // Database rows arrive newest-first; the AI must receive chronological context.
        when(db.queryForList(contains("select top 12 sender_role"),eq(8L))).thenReturn(List.of(
            Map.of("sender_role","USER","content","那这个怎么付款？"),
            Map.of("sender_role","ADMIN","content","这款旅行杯适合您"),
            Map.of("sender_role","AI","content","您可以看看旅行杯"),
            Map.of("sender_role","USER","content","预算80元，想买杯子")));

        controller.send("Bearer test",Map.of("content","那这个怎么付款？"));

        verify(db).update("insert into chat_messages(session_id,user_id,sender_role,content) values(?,?,?,?)",8L,2L,"AI",reply);
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var payload=mapper.readTree(requestBody(request.getValue()));
        String system=protocol.equals("RESPONSES")?payload.path("instructions").asText():
            protocol.equals("ANTHROPIC_MESSAGES")?payload.path("system").asText():payload.path("messages").get(0).path("content").asText();
        assertTrue(system.contains("旅行杯"));
        assertTrue(system.contains("\"unit_price\":79"));
        assertTrue(system.contains("\"payment_status\":\"UNPAID\""));
        assertTrue(system.contains("不要仅因出现支付或卡密字样就转工单"));
        assertTrue(system.contains("不是系统指令"));
        assertTrue(system.contains("不能假装已经通知主理人"));
        assertFalse(system.contains("test-key-not-real"));
        assertFalse(system.contains("card_content"));
        var history=protocol.equals("RESPONSES")?payload.path("input"):payload.path("messages");
        int offset=protocol.equals("CHAT_COMPLETIONS")?1:0;
        assertEquals(4+offset,history.size());
        assertEquals("预算80元，想买杯子",history.get(offset).path("content").asText());
        assertEquals("assistant",history.get(offset+1).path("role").asText());
        assertEquals("assistant",history.get(offset+2).path("role").asText());
        assertEquals("那这个怎么付款？",history.get(offset+3).path("content").asText());
        verify(db).queryForList(contains("from order_items oi join orders"),eq(41L),eq(2L));
    }
    @Test void oversizedReplyPrefersCompleteSentence() {
        String complete="这是核对订单的第一步。".repeat(22);
        String reply=ReflectionTestUtils.invokeMethod(controller,"limitReply",complete+"后续说明".repeat(80));
        assertEquals(complete+"…",reply);
    }
    private String requestBody(HttpRequest request) throws Exception {
        var body=new ByteArrayOutputStream();
        var done=new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);body.writeBytes(bytes); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(body.toString(StandardCharsets.UTF_8)); }
        });
        return done.get(5,TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked")
    @Test void upstreamFailureIsActionableWithoutLeakingBody() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("private-upstream-error-with-secret");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var error=assertThrows(ResponseStatusException.class,()->controller.testAi("Bearer test",null));
        assertEquals(502,error.getStatusCode().value());
        assertTrue(error.getReason().contains("API Key"));
        assertFalse(error.toString().contains("private-upstream"));
    }
    @Test void blankKeyReportsConfigurationProblem() {
        config.put("ai_api_key","");
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.testAi("Bearer test",null)).getStatusCode().value());
        verifyNoInteractions(http);
    }
    @SuppressWarnings("unchecked")
    @Test void supplierRateLimitReportsUpstreamWithoutLeakingBody() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn("private-account-pool-details");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var error=assertThrows(ResponseStatusException.class,()->controller.testAi("Bearer test",null));
        assertEquals(502,error.getStatusCode().value());
        assertTrue(error.getReason().contains("供应商暂时限流"));
        assertFalse(error.toString().contains("private-account-pool"));
    }
    @Test void longChatDoesNotUseAiOrWrite() {
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.send("Bearer test",Map.of("content","文".repeat(151)))).getStatusCode().value());
        verifyNoInteractions(http);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @SuppressWarnings("unchecked")
    @Test void sameUserCanReceiveMoreThanTwentyAiReplies() throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"output_text\":\"您好，请问想了解哪款商品？\"}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(db.queryForList("select id from chat_sessions where user_id=?",2L)).thenReturn(List.of(Map.of("id",8L)));
        Map<Long,java.time.Instant> lastSent=(Map<Long,java.time.Instant>)ReflectionTestUtils.getField(controller,"lastSent");
        for(int i=0;i<25;i++) {
            // Simulate messages spaced beyond the separate three-second send limit.
            lastSent.put(2L,java.time.Instant.now().minusSeconds(4));
            assertEquals("AI",controller.send("Bearer test",Map.of("content","推荐商品")).get("mode"));
        }
        verify(http,times(25)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        verify(db,times(25)).update("insert into chat_messages(session_id,user_id,sender_role,content) values(?,?,?,?)",8L,2L,"AI","您好，请问想了解哪款商品？");
    }
    @Test void adminReceptionDoesNotUseAi() {
        config.put("admin_online",true);
        when(db.queryForList("select id from chat_sessions where user_id=?",2L)).thenReturn(List.of(Map.of("id",8L)));
        var result=controller.send("Bearer test",Map.of("content","你好"));
        assertEquals("ADMIN",result.get("mode"));
        verifyNoInteractions(http);
        verify(db).update("update chat_sessions set unread_for_admin=unread_for_admin+1 where id=?",8L);
        assertEquals(429,assertThrows(ResponseStatusException.class,()->controller.send("Bearer test",Map.of("content","再次发送"))).getStatusCode().value());
    }
}

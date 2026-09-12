package com.example.abac.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AbacClient（document 侧 PDP 客户端）单测：
 * 裁决视图解析、批量顺序映射、以及"PDP 异常必须安全失败"的契约。
 */
class AbacClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final ObjectMapper json = new ObjectMapper();
    private AbacClient client;

    @BeforeEach
    void setUp() {
        client = new AbacClient(restTemplate);
    }

    private static Map<String, Object> subject() {
        return Map.of("username", "alice", "clearance", 7);
    }

    private static Map<String, Object> resource() {
        return Map.of("type", "DOCUMENT", "id", "12");
    }

    @Test
    void decide_parsesPermitView() throws Exception {
        server.expect(requestTo("http://abac-service/api/decide"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json.writeValueAsString(Map.of(
                                "effect", "PERMIT", "permitted", true,
                                "policyId", 30, "policyName", "DOC-30",
                                "reason", "permitted by policy: DOC-30")),
                        MediaType.APPLICATION_JSON));

        AbacClient.DecisionView view = client.decide(subject(), resource(), "READ");

        assertTrue(view.permitted());
        assertEquals("PERMIT", view.effect());
        assertEquals(30L, view.policyId());
        assertEquals("DOC-30", view.policyName());
    }

    @Test
    void decide_parsesDenyView() throws Exception {
        server.expect(requestTo("http://abac-service/api/decide"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json.writeValueAsString(Map.of(
                                "effect", "DENY", "permitted", false,
                                "policyId", 90, "policyName", "DOC-90",
                                "reason", "clearance too low")),
                        MediaType.APPLICATION_JSON));

        AbacClient.DecisionView view = client.decide(subject(), resource(), "READ");

        assertFalse(view.permitted());
        assertEquals("DENY", view.effect());
        assertEquals("clearance too low", view.reason());
    }

    @Test
    void decide_emptyBodyIsTreatedAsDeny() {
        server.expect(requestTo("http://abac-service/api/decide"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        AbacClient.DecisionView view = client.decide(subject(), resource(), "READ");

        assertFalse(view.permitted());
        assertEquals("empty PDP response", view.reason());
    }

    @Test
    void decide_serverErrorFailsClosed() {
        server.expect(requestTo("http://abac-service/api/decide"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThrows(AbacClient.PdpUnavailableException.class,
                () -> client.decide(subject(), resource(), "READ"));
    }

    @Test
    void decideBatch_mapsResultsInRequestOrder() throws Exception {
        server.expect(requestTo("http://abac-service/api/decide/batch"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json.writeValueAsString(List.of(
                                Map.of("effect", "PERMIT", "permitted", true),
                                Map.of("effect", "DENY", "permitted", false))),
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> requests = List.of(
                Map.of("subject", subject(), "resource", resource(), "action", "READ"),
                Map.of("subject", subject(), "resource", resource(), "action", "READ"));
        List<AbacClient.DecisionView> views = client.decideBatch(requests);

        assertEquals(2, views.size());
        assertTrue(views.get(0).permitted());
        assertFalse(views.get(1).permitted());
    }

    @Test
    void decideBatch_emptyBodyReturnsEmptyList() {
        server.expect(requestTo("http://abac-service/api/decide/batch"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        List<AbacClient.DecisionView> views = client.decideBatch(List.of(Map.of()));

        assertTrue(views.isEmpty());
    }

    @Test
    void decideBatch_emptyRequestsShortCircuitsWithoutCallingPdp() {
        List<AbacClient.DecisionView> views = client.decideBatch(List.of());
        assertTrue(views.isEmpty());
        server.verify();
    }
}

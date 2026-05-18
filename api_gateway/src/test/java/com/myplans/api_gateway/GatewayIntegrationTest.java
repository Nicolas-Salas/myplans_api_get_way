package com.myplans.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
                "AUTH_SERVICE_URI=http://localhost:1",
                "CORE_SERVICE_URI=http://localhost:1",
                "AUDIT_SERVICE_URI=http://localhost:1"
})
class GatewayIntegrationTest {

        @Autowired
        private WebTestClient webTestClient;

        @Test
        void givenNoToken_whenAccessProtectedCore_thenReturn401() {
                webTestClient.get()
                                .uri("/api/v1/planos")
                                .exchange()
                                .expectStatus().isUnauthorized()
                                .expectHeader().contentType("application/json")
                                .expectBody()
                                .jsonPath("$.status").isEqualTo(401)
                                .jsonPath("$.message").exists();
        }

        @Test
        void givenNoToken_whenAccessProtectedAuthMe_thenReturn401() {
                webTestClient.get()
                                .uri("/api/auth/me")
                                .exchange()
                                .expectStatus().isUnauthorized()
                                .expectBody()
                                .jsonPath("$.status").isEqualTo(401);
        }

        @Test
        void givenExpiredToken_whenAccessProtected_thenReturn401WithExpiredMessage() {
                String expired = TestJwtHelper.expiredToken("admin@test.com", 1, List.of("ROLE_ADMIN"));
                webTestClient.get()
                                .uri("/api/v1/planos")
                                .header("Authorization", "Bearer " + expired)
                                .exchange()
                                .expectStatus().isUnauthorized()
                                .expectBody()
                                .jsonPath("$.message").value(msg ->
                                // Acepta varias variantes en castellano
                                org.assertj.core.api.Assertions.assertThat(((String) msg).toLowerCase())
                                                .containsAnyOf("expirado", "sesión", "inicia"));
        }

        @Test
        void givenInvalidToken_whenAccessProtected_thenReturn401WithInvalidMessage() {
                webTestClient.get()
                                .uri("/api/v1/planos")
                                .header("Authorization", "Bearer token-falso-no-firmado")
                                .exchange()
                                .expectStatus().isUnauthorized()
                                .expectBody()
                                .jsonPath("$.message")
                                .value(msg -> org.assertj.core.api.Assertions.assertThat(((String) msg).toLowerCase())
                                                .containsAnyOf("token", "sesión", "inicia"));
        }

        @Test
        void givenNoToken_whenLogin_thenForwarded() {
                webTestClient.post()
                                .uri("/api/auth/login")
                                .bodyValue("{\"email\":\"x\",\"password\":\"y\"}")
                                .header("Content-Type", "application/json")
                                .exchange()
                                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                                                .isNotEqualTo(401)
                                                .isBetween(500, 599));
        }

        @Test
        void givenNoToken_whenSwaggerUi_thenAccessible() {
                webTestClient.get()
                                .uri("/swagger-ui.html")
                                .exchange()
                                .expectStatus()
                                .value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
        }

        @Test
        void givenNoToken_whenAccessProtectedAudit_thenReturn401() {
                webTestClient.get()
                                .uri("/api/v1/historial/tag/42")
                                .exchange()
                                .expectStatus().isUnauthorized()
                                .expectBody()
                                .jsonPath("$.status").isEqualTo(401)
                                .jsonPath("$.message").exists();
        }

        @Test
        void givenValidToken_whenAccessAudit_thenForwarded() {
                String token = TestJwtHelper.validToken(
                                "sup@test.com", 2, List.of("ROLE_SUPERVISOR"));

                webTestClient.get()
                                .uri("/api/v1/historial/tag/42")
                                .header("Authorization", "Bearer " + token)
                                .exchange()
                                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                                                .isNotEqualTo(401)
                                                .isBetween(500, 599));
        }

        @Test
        void givenNoToken_whenAuditDocs_thenAccessible() {
                webTestClient.get()
                                .uri("/api-docs-audit")
                                .exchange()
                                .expectStatus()
                                .value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
        }
}

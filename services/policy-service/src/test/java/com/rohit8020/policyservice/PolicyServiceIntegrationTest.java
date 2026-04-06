package com.rohit8020.policyservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.rohit8020.policyservice.dto.IssuePolicyRequest;
import com.rohit8020.policyservice.dto.PolicyResponse;
import com.rohit8020.policyservice.entity.OutboxEvent;
import com.rohit8020.policyservice.entity.OutboxStatus;
import com.rohit8020.policyservice.entity.UserRole;
import com.rohit8020.policyservice.repository.OutboxEventRepository;
import com.rohit8020.policyservice.repository.PolicyRepository;
import com.rohit8020.policyservice.service.PolicyService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@SpringBootTest
class PolicyServiceIntegrationTest {

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void issuePolicyIsIdempotentAndPersistsOutboxEvent() {
        JwtAuthenticationToken agent = authentication(42L, "agent-user", UserRole.AGENT);
        IssuePolicyRequest request = new IssuePolicyRequest(
                101L, 42L, new BigDecimal("199.99"),
                LocalDate.now(), LocalDate.now().plusDays(30));

        PolicyResponse first = policyService.issuePolicy(agent, request, "issue-policy-key");
        PolicyResponse replayed = policyService.issuePolicy(agent, request, "issue-policy-key");

        assertThat(first.id()).isEqualTo(replayed.id());
        assertThat(policyRepository.count()).isEqualTo(1);

        OutboxEvent event = outboxEventRepository.findAll().get(0);
        assertThat(event.getEventType()).isEqualTo("POLICY_ISSUED");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    private JwtAuthenticationToken authentication(Long userId, String username, UserRole role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(username)
                .claim("userId", userId)
                .claim("role", role.name())
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}

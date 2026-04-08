package com.rohit8020.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.rohit8020.authservice.entity.User;
import com.rohit8020.authservice.entity.UserRole;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtUtilTest {

    private static final String PRIVATE_KEY = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDf/N8a7nu2tv0iaK+O13bZ2Q2CPWLuvM59focSpQodjjW6+WxT2DtyxSDF5bY0ORa/ve1eBG2kzEhaidqq8fZL2lfvu1169KVzJTpzXVC2+dRA8SY9Xwt6nLGdp+Y2l7iPZgKuJog8CURY5d0bE55cf37VR2qFS5jZG9Efyu/rl8tgy0hIorOtLV4EvlT/6zhoZmYdkMkSBtfxdWn+UgDvIDZudThgjFr+CYR5wmLMV6h5+wm73kxDAGNREjCAYujhep482HXjIT0c44wr99D5KHxjmW5xfTHh9Bi3+pWZMrWZIP6s9FQQi5qsKrNzI0JbN5riVoNRpNS8zd2HL/9lAgMBAAECggEAEoU4lw6SPP18nkWngykxNa+duepkqFwFEI7aUSduLUXxjubUocp+wI1orucAzb4iXFMyZzmjWEL065mQ8rK5kbQ5h67HerpzsLrquO3FwK9qcRPk0c0ZUveAXzUUvb2wqsNoTtPhpAyEcdD7MI6Kzlf+GdlYcdzvmT9p0gC9cvkX66h22Dq5eu3Cs2tUOnPaI9idySwotgV3o5k61mX4+ZxR/VV2hZmw5pLE7Gvc5QUhkNtLpg2Mk7UIIadZ7gD9oQa0y6IoRXaKJcByOlpBVpc6hfEvheFO/ww3WhQIMeBQ5v/JJMxAgLO7aaNjcZb4svGvwgjtcT/mG1HifZ3reQKBgQD8Riwr7+hiEqj2TDCSGEPJTa7AoAFGdlds6ngyZFmp4DhSzPIupiHLfcyDG2m8teqd0rpuhbrRkevl0WaQ0gAOj0uXPUm+V3t5k2b920MVeEmMtLlO5xO7+tryLV+yCp0QCTB4vADUmchfsIg4crloI1k0AtFB5SNMox2+jA5O/QKBgQDjS8AtDqEpERRAK8J27DOCgOjBjOKkS3/Yg+QhfXfzTZDuiFaOA62r7JRhIXYn9xBgYNhHt/M1J78gtfTEG4zbPc9/MniDis5b7PtBeVa5TFWtaAzLyBYbqGPraH93wlpyg6jZuIaPQbbwKm7qflpFxrzWtzzv6E2Uh8cc+BzCiQKBgQDsuAwKznuIS2owcx3AePRimHo6VencbH9svDc9UdyxAqzXRWibTVi40bpt6/M0GJ+mqG4391RuAjcQ14Uer29OOjf8Go5wBTbfxekGnBA+vTiGx65602o5IhMA3ILHVh47ReQt5nwBAqx63fN0xHIlvcWegGZLJvAQoZ7vhgyHIQKBgGThNv8IcjDG2sUMZvffJ5FxY1ycCe8/bxOKnhLbHATJVVz49+l56nfWvZhKgKWGOyd7dCKImxHpfSOofmUXkTGxQknC/cfsMGCOUomhsAL3xUL8XkmHmYBXAVn2/DQL95bBpoxTIK2uTeJUAvxibfBwH1nw48Pax6v3g0DxQdp5AoGBAME4wY9MtrKR/mKUwXYEjU+MxHlWPwm/7LPU/oMDHAcLDeuPorTsn6C9kzNr3LKULGUNwHcRfyQeBlgS/3eeQ6RGeDzW9DA5bQW4xyrVquFV9/EssxTEwxxffO0LKSuHBWzft6Qs7l32DFVonE1rkkbWbgtbkxh3PFuyLjAHp8ar";
    private static final String PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3/zfGu57trb9Imivjtd22dkNgj1i7rzOfX6HEqUKHY41uvlsU9g7csUgxeW2NDkWv73tXgRtpMxIWonaqvH2S9pX77tdevSlcyU6c11QtvnUQPEmPV8LepyxnafmNpe4j2YCriaIPAlEWOXdGxOeXH9+1UdqhUuY2RvRH8rv65fLYMtISKKzrS1eBL5U/+s4aGZmHZDJEgbX8XVp/lIA7yA2bnU4YIxa/gmEecJizFeoefsJu95MQwBjURIwgGLo4XqePNh14yE9HOOMK/fQ+Sh8Y5lucX0x4fQYt/qVmTK1mSD+rPRUEIuarCqzcyNCWzea4laDUaTUvM3dhy//ZQIDAQAB";

    @Test
    void userTokenRoundTripsClaimsAndUsername() {
        JwtUtil jwtUtil = jwtUtil(PRIVATE_KEY, PUBLIC_KEY);
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setRole(UserRole.ADMIN);

        String token = jwtUtil.generateToken(user);
        Claims claims = jwtUtil.extractClaims(token);

        assertThat(jwtUtil.extractUsername(token)).isEqualTo("alice");
        assertThat(claims.get("userId", Integer.class)).isEqualTo(7);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.get("token_use", String.class)).isEqualTo("user");
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:8081");
        assertThat(jwtUtil.getAccessExpiration()).isEqualTo(3_600_000L);
    }

    @Test
    void clientTokenContainsClientClaims() {
        JwtUtil jwtUtil = jwtUtil(PRIVATE_KEY, PUBLIC_KEY);

        String token = jwtUtil.generateClientToken("claims-service", "policy.projection.read");
        Claims claims = jwtUtil.extractClaims(token);

        assertThat(claims.getSubject()).isEqualTo("claims-service");
        assertThat(claims.get("scope", String.class)).isEqualTo("policy.projection.read");
        assertThat(claims.get("client_id", String.class)).isEqualTo("claims-service");
        assertThat(claims.get("token_use", String.class)).isEqualTo("client");
        assertThat(jwtUtil.getClientAccessExpiration()).isEqualTo(300_000L);
    }

    @Test
    void refreshTokenExpiryUsesConfiguredDuration() {
        JwtUtil jwtUtil = jwtUtil(PRIVATE_KEY, PUBLIC_KEY);

        long lowerBound = Instant.now().plusSeconds(86_399).toEpochMilli();
        long upperBound = Instant.now().plusSeconds(86_401).toEpochMilli();

        assertThat(jwtUtil.refreshTokenExpiry().getTime()).isBetween(lowerBound, upperBound);
    }

    @Test
    void jwkSetExposesPublicKeyMetadata() {
        JwtUtil jwtUtil = jwtUtil(PRIVATE_KEY, PUBLIC_KEY);

        JWKSet jwkSet = jwtUtil.jwkSet();

        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeys().get(0).getKeyID()).isEqualTo("test-auth-key");
        assertThat(jwkSet.toJSONObject()).containsKey("keys");
    }

    @Test
    void constructorAcceptsKeysWithWhitespace() {
        String formattedPrivateKey = PRIVATE_KEY.substring(0, 80) + "\n" + PRIVATE_KEY.substring(80);
        String formattedPublicKey = PUBLIC_KEY.substring(0, 80) + " \n " + PUBLIC_KEY.substring(80);

        JwtUtil jwtUtil = jwtUtil(formattedPrivateKey, formattedPublicKey);

        String token = jwtUtil.generateClientToken("claims-service", "claims.write");
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("claims-service");
    }

    @Test
    void constructorRejectsInvalidPrivateKey() {
        assertThatThrownBy(() -> jwtUtil(Base64.getEncoder().encodeToString("bad".getBytes()), PUBLIC_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private key");
    }

    @Test
    void constructorRejectsInvalidPublicKey() {
        assertThatThrownBy(() -> jwtUtil(PRIVATE_KEY, Base64.getEncoder().encodeToString("bad".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public key");
    }

    private JwtUtil jwtUtil(String privateKey, String publicKey) {
        return new JwtUtil(
                privateKey,
                publicKey,
                "test-auth-key",
                "http://localhost:8081",
                3_600_000L,
                86_400_000L,
                300_000L
        );
    }
}

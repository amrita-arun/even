package dev.even.auth;

import dev.even.support.TestJwtDecoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, TestJwtDecoderConfig.class})
@TestPropertySource(properties = {
    "SUPABASE_URL=https://test.supabase.co",
    "even.supabase.jwt-secret=test-secret-key-at-least-thirty-two-chars"
})
class AuthSecurityTest {

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  UserRepository users;

  @Test
  void meWithoutTokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void meWithJwtReturnsProfile() throws Exception {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(users.upsert(eq(id), eq("amrita@example.com")))
        .thenReturn(new UserRepository.UserRecord(id, "amrita@example.com", null));

    mockMvc.perform(get("/api/auth/me")
            .with(jwt().jwt(token -> token
                .subject(id.toString())
                .claim("email", "amrita@example.com"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.email").value("amrita@example.com"));
  }
}

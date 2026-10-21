package dev.even.health;

import dev.even.auth.SecurityConfig;
import dev.even.support.TestJwtDecoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, TestJwtDecoderConfig.class})
@TestPropertySource(properties = {
    "SUPABASE_URL=https://test.supabase.co",
    "even.supabase.jwt-secret=test-secret-key-at-least-thirty-two-chars"
})
class HealthControllerTest {

  @Autowired
  MockMvc mockMvc;

  @Test
  void healthIsPublic() throws Exception {
    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
  }
}

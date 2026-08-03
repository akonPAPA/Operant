package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Connection-scoped Telegram webhook verifier. When the server-configured Telegram secret token is
 * present and the connection enforces signature/provider mode, compares
 * {@code X-Telegram-Bot-Api-Secret-Token}. Otherwise defers to the shared fail-closed contract.
 */
@Component
public class TelegramWebhookVerifier extends AbstractProviderWebhookVerifier {
  private final String configuredSecretToken;

  public TelegramWebhookVerifier() {
    this("", WebhookVerificationAuthority.forTests(false, false));
  }

  public TelegramWebhookVerifier(String configuredSecretToken, WebhookVerificationAuthority authority) {
    super(authority);
    this.configuredSecretToken = configuredSecretToken == null ? "" : configuredSecretToken;
  }

  @Autowired
  public TelegramWebhookVerifier(
      @Value("${orderpilot.bot.telegram.webhook-secret-token:}") String configuredSecretToken,
      WebhookVerificationAuthority authority,
      Environment environment) {
    this(configuredSecretToken, authority);
  }

  @Override
  public ChannelProviderType providerType() {
    return ChannelProviderType.TELEGRAM;
  }

  @Override
  public VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload) {
    String mode = connection.getWebhookVerificationMode();
    boolean enforcing = "SIGNATURE_HEADER".equals(mode) || "PROVIDER_SPECIFIC".equals(mode);
    if (enforcing && !configuredSecretToken.isBlank()) {
      String presented = header(headers, "x-telegram-bot-api-secret-token");
      if (!configuredSecretToken.equals(presented)) {
        return VerificationResult.rejected("Telegram secret token is missing or invalid");
      }
      return VerificationResult.configuredVerifyOnly("Telegram secret token accepted");
    }
    return super.verify(connection, headers, rawPayload);
  }
}

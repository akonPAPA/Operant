import type { ApiResult, BotConversation, BotConversationDetail } from "./bot-runtime-api.ts";

export const MAX_BOT_CONVERSATION_DETAILS = 20;

export type BotConversationDetailReaders = {
  getJson<T>(path: string): Promise<ApiResult<T>>;
  getNullable<T>(path: string): Promise<{ data: T | null; error?: string }>;
};

export async function listBotConversationDetailsWithReaders(
  readers: BotConversationDetailReaders
): Promise<ApiResult<BotConversationDetail[]>> {
  const conversations = await readers.getJson<BotConversation[]>(
    "/api/v1/bot-runtime/conversations"
  );
  const summaries = Array.isArray(conversations.data) ? conversations.data : [];
  const detailResults = await Promise.all(
    summaries.slice(0, MAX_BOT_CONVERSATION_DETAILS).map(async (conversation) => {
      const detail = await readers.getNullable<BotConversationDetail>(
        `/api/v1/bot-runtime/conversations/${encodeURIComponent(conversation.id)}`
      );
      if (!isBotConversationDetail(detail.data)) {
        return {
          data: null,
          error: detail.error ?? `Bot conversation ${conversation.id} returned an invalid detail shape.`
        };
      }
      return detail;
    })
  );
  const errors = [conversations.error, ...detailResults.map((item) => item.error)].filter(Boolean);
  return {
    data: detailResults.flatMap((item) => (item.data ? [item.data] : [])),
    error: errors.length > 0 ? errors.join(" ") : undefined
  };
}

function isBotConversationDetail(value: unknown): value is BotConversationDetail {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<BotConversationDetail>;
  return Boolean(candidate.conversation)
    && Array.isArray(candidate.messages)
    && Array.isArray(candidate.handoffs)
    && Array.isArray(candidate.responseDrafts);
}
import type { SSECallbacks } from "./api";
import type {
  ConversationErrorEventDto,
  ConversationNodeUpdateEventDto,
  ConversationSnapshotEventDto,
} from "../types/dto";

export type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto
  | ConversationErrorEventDto;

export type ConversationStreamConnector = (
  url: string,
  callbacks: SSECallbacks<ConversationStreamEvent>,
  options: { signal: AbortSignal },
) => Promise<void>;

interface ConversationDetailCallbacks {
  onSnapshot: (event: ConversationSnapshotEventDto) => void;
  onNodeUpdate: (event: ConversationNodeUpdateEventDto) => void;
  onBusinessError: (message: string) => void;
  onInitialError: (error?: Error) => void;
  onStreamError: (error: Error) => void;
}

/** 首个 SSE 快照是唯一初始化来源；切换会话后，旧连接的所有回调均失效。 */
export function subscribeToConversationDetail(
  conversationId: string,
  callbacks: ConversationDetailCallbacks,
  connect: ConversationStreamConnector,
): () => void {
  const controller = new AbortController();
  let active = true;
  let hasSnapshot = false;
  let initialErrorReported = false;

  const failInitialLoad = (error?: Error) => {
    if (!active || hasSnapshot || initialErrorReported) return;
    initialErrorReported = true;
    callbacks.onInitialError(error);
  };
  const onError = (error: Error) => {
    if (!active) return;
    callbacks.onStreamError(error);
    failInitialLoad(error);
  };

  const streamCallbacks: SSECallbacks<ConversationStreamEvent> = {
    onMessage: ({ event, data }) => {
      if (!active) return;
      if (event === "error" && data.type === "error") {
        callbacks.onBusinessError(data.message);
      } else if (event === "snapshot" && data.type === "snapshot") {
        if (data.conversation.id !== conversationId) return;
        hasSnapshot = true;
        callbacks.onSnapshot(data);
      } else if (event === "node_update" && data.type === "node_update") {
        if (!hasSnapshot || data.conversationId !== conversationId) return;
        callbacks.onNodeUpdate(data);
      }
    },
    onError,
    // 正常 EOF 也可能发生在首个快照前，不能让页面永久停留在加载状态。
    onClose: () => failInitialLoad(),
  };

  void (async () => {
    try {
      await connect(`conversations/${conversationId}/stream`, streamCallbacks, {
        signal: controller.signal,
      });
    } catch (error) {
      onError(error instanceof Error ? error : new Error(String(error)));
    }
  })();

  return () => {
    active = false;
    controller.abort();
  };
}

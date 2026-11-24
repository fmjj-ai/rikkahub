import * as assert from "node:assert/strict";
import { test } from "node:test";
import {
  subscribeToConversationDetail,
  type ConversationStreamConnector,
} from "../app/services/conversation-detail-stream";
import type {
  ConversationNodeUpdateEventDto,
  ConversationSnapshotEventDto,
} from "../app/types/dto";

function fixture() {
  let stream!: Parameters<ConversationStreamConnector>[1];
  let signal!: AbortSignal;
  const urls: string[] = [];
  const events: string[] = [];
  const initialErrors: Array<Error | undefined> = [];
  const connect: ConversationStreamConnector = async (url, callbacks, options) => {
    urls.push(url);
    stream = callbacks;
    signal = options.signal;
  };
  const dispose = subscribeToConversationDetail(
    "conversation",
    {
      onSnapshot: () => events.push("snapshot"),
      onNodeUpdate: () => events.push("node"),
      onBusinessError: (message) => events.push(`business:${message}`),
      onInitialError: (error) => initialErrors.push(error),
      onStreamError: () => events.push("stream-error"),
    },
    connect,
  );
  return { stream, signal, urls, events, initialErrors, dispose };
}

function snapshot(id = "conversation"): ConversationSnapshotEventDto {
  return {
    type: "snapshot",
    seq: 1,
    serverTime: 100,
    conversation: {
      id,
      assistantId: "assistant",
      title: "title",
      messages: [],
      chatSuggestions: [],
      isPinned: false,
      createAt: 1,
      updateAt: 2,
      isGenerating: true,
    },
  };
}

function nodeUpdate(id = "conversation"): ConversationNodeUpdateEventDto {
  return {
    type: "node_update",
    seq: 2,
    conversationId: id,
    nodeId: "node",
    nodeIndex: 0,
    node: { id: "node", messages: [], selectIndex: 0 },
    updateAt: 3,
    isGenerating: false,
    serverTime: 101,
  };
}

test("首个快照引导详情，之前的节点增量不能伪造已初始化状态", () => {
  const state = fixture();
  state.stream.onMessage({ event: "node_update", data: nodeUpdate() });
  assert.deepEqual(state.events, []);
  state.stream.onMessage({ event: "snapshot", data: snapshot() });
  state.stream.onMessage({ event: "node_update", data: nodeUpdate() });
  assert.deepEqual(state.events, ["snapshot", "node"]);
  assert.deepEqual(state.urls, ["conversations/conversation/stream"]);
  state.dispose();
});

test("首帧前连接异常只报告一次，EOF 不覆盖原始错误", () => {
  const state = fixture();
  const error = new Error("connection failed");
  state.stream.onError?.(error);
  state.stream.onClose?.();
  assert.deepEqual(state.initialErrors, [error]);
  state.dispose();
});

test("首帧前正常关闭也结束加载", () => {
  const state = fixture();
  state.stream.onClose?.();
  assert.deepEqual(state.initialErrors, [undefined]);
  state.dispose();
});

test("已有快照时断线不把成功详情变成初始化失败", () => {
  const state = fixture();
  state.stream.onMessage({ event: "snapshot", data: snapshot() });
  state.stream.onError?.(new Error("disconnected"));
  state.stream.onClose?.();
  assert.deepEqual(state.initialErrors, []);
  assert.deepEqual(state.events, ["snapshot", "stream-error"]);
  state.dispose();
});

test("卸载后取消连接，迟到消息和关闭回调均失效", () => {
  const state = fixture();
  state.dispose();
  assert.equal(state.signal.aborted, true);
  state.stream.onMessage({ event: "snapshot", data: snapshot() });
  state.stream.onError?.(new Error("late"));
  state.stream.onClose?.();
  assert.deepEqual(state.events, []);
  assert.deepEqual(state.initialErrors, []);
});

test("忽略不属于当前会话的快照和增量，仍保留业务错误通知", () => {
  const state = fixture();
  state.stream.onMessage({ event: "snapshot", data: snapshot("other") });
  state.stream.onMessage({ event: "snapshot", data: snapshot() });
  state.stream.onMessage({ event: "node_update", data: nodeUpdate("other") });
  state.stream.onMessage({ event: "error", data: { type: "error", message: "tool failed" } });
  assert.deepEqual(state.events, ["snapshot", "business:tool failed"]);
  state.dispose();
});

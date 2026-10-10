import { useMemo, useState } from "react";
import { conversationKey, relativeDate } from "../format";
import type { ConnectionStatus, Conversation, ConversationCreateTarget } from "../types";
import { Icon } from "./Icon";

interface ConversationSidebarProps {
  conversations: Conversation[];
  selected: Conversation | null;
  connectionStatus: ConnectionStatus;
  onCreate: (target: ConversationCreateTarget) => void;
  onSelect: (conversation: Conversation) => void;
  onDelete: (conversation: Conversation) => Promise<void>;
}

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  online: "Connected to TaiXu",
  offline: "Connection lost; retrying",
  connecting: "Connecting to live updates",
};

export function ConversationSidebar({
  conversations,
  selected,
  connectionStatus,
  onCreate,
  onSelect,
  onDelete,
}: ConversationSidebarProps) {
  const [search, setSearch] = useState("");
  const query = search.trim().toLowerCase();
  const visible = useMemo(() => conversations.filter((conversation) => (
    !query || [conversation.title, conversation.summary, conversation.lastMessage]
      .some((value) => String(value ?? "").toLowerCase().includes(query))
  )), [conversations, query]);

  return (
    <aside className="conversation-pane">
      <div className="conversation-toolbar">
        <div className="search-field">
          <Icon name="search" size={17} />
          <input
            type="search"
            aria-label="Search Agent Hub conversations"
            placeholder="Search conversations"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          {search && (
            <button className="search-clear" type="button" aria-label="Clear search" onClick={() => setSearch("")}>
              <Icon name="x" size={14} />
            </button>
          )}
        </div>
        <button
          className="sidebar-round-button primary"
          type="button"
          aria-label="New Agent Hub task"
          title="New Agent Hub task"
          onClick={() => onCreate({ mode: "normal" })}
        >
          <Icon name="plus" size={18} />
        </button>
      </div>

      <div className="conversation-list" aria-live="polite">
        <div className="conversation-section-header">
          <Icon name="agent" size={14} />
          <span>Agent Hub conversations</span>
          <small>{visible.length}</small>
        </div>
        {!visible.length && (
          <div className="list-empty">
            <Icon name={query ? "search" : "agent"} size={24} />
            <strong>{query ? "No matching conversations found" : "No conversations yet"}</strong>
            <span>{query ? "Try another keyword" : "Click the top-right button to start a task"}</span>
          </div>
        )}
        {visible.map((conversation) => {
          const active = conversationKey(conversation) === conversationKey(selected);
          return (
            <div className={`conversation-item-row${active ? " active" : ""}`} key={conversationKey(conversation)}>
              <button className="conversation-item" type="button" onClick={() => onSelect(conversation)}>
                <span className="conversation-item-heading">
                  <strong>{conversation.title || "New conversation"}</strong>
                  <time>{relativeDate(conversation.updatedAt)}</time>
                </span>
              </button>
              <button
                className="conversation-delete-button"
                type="button"
                aria-label={`删除“${conversation.title || "New conversation"}”`}
                title="Delete conversation"
                onClick={() => void onDelete(conversation)}
              >
                <Icon name="trash" size={14} />
              </button>
            </div>
          );
        })}
      </div>

      <footer className="connection-footer">
        <span className={`connection-dot ${connectionStatus === "connecting" ? "" : connectionStatus}`} />
        <span>{STATUS_LABELS[connectionStatus]}</span>
      </footer>
    </aside>
  );
}

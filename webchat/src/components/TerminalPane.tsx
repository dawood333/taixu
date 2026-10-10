import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { useCallback, useEffect, useRef, useState } from "react";
import { terminalStreamUrl } from "../api";
import {
  closeTerminal,
  createTerminal,
  listTerminals,
  resizeTerminal,
  sendTerminalInput,
} from "../terminalApi";
import type { WebTerminalInfo } from "../types";
import { Icon } from "./Icon";

type LinkState = "offline" | "connecting" | "online" | "closed";

const TERMINAL_THEME = {
  background: "#0d1117",
  foreground: "#c9d1d9",
  cursor: "#58a6ff",
  cursorAccent: "#0d1117",
  selectionBackground: "#264f78",
  black: "#484f58",
  red: "#ff7b72",
  green: "#3fb950",
  yellow: "#d29922",
  blue: "#58a6ff",
  magenta: "#bc8cff",
  cyan: "#39c5cf",
  white: "#b1bac4",
  brightBlack: "#6e7681",
  brightRed: "#ffa198",
  brightGreen: "#56d364",
  brightYellow: "#e3b341",
  brightBlue: "#79c0ff",
  brightMagenta: "#d2a8ff",
  brightCyan: "#56d4dd",
  brightWhite: "#f0f6fc",
};

function base64ToBytes(encoded: string): Uint8Array {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error ?? "Terminal operation failed");
}

interface TerminalPaneProps {
  /** Whether the panel is visible (desktop switching / mobile panes). Pause fitting while hidden but keep the session alive. */
  active: boolean;
}

export function TerminalPane({ active }: TerminalPaneProps) {
  const [terminals, setTerminals] = useState<WebTerminalInfo[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [link, setLink] = useState<LinkState>("offline");
  const [notice, setNotice] = useState("");
  const containerRef = useRef<HTMLDivElement | null>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const sourceRef = useRef<EventSource | null>(null);
  const activeIdRef = useRef<string | null>(null);
  const resizeTimerRef = useRef<number | null>(null);

  // Create the xterm instance once; clear it with term.reset() when switching sessions.
  useEffect(() => {
    const term = new Terminal({
      fontFamily: '"Cascadia Code", "JetBrains Mono", Consolas, "Courier New", monospace',
      fontSize: 13,
      lineHeight: 1.2,
      cursorBlink: true,
      scrollback: 5000,
      theme: TERMINAL_THEME,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    termRef.current = term;
    fitRef.current = fit;
    if (containerRef.current) term.open(containerRef.current);
    const inputDisposable = term.onData((data) => {
      const id = activeIdRef.current;
      if (id) void sendTerminalInput(id, new TextEncoder().encode(data)).catch(() => undefined);
    });
    return () => {
      inputDisposable.dispose();
      sourceRef.current?.close();
      sourceRef.current = null;
      term.dispose();
      termRef.current = null;
      fitRef.current = null;
    };
  }, []);

  // On session switch, reopen the SSE stream. EventSource reconnects automatically after tab hiding or network interruptions.
  useEffect(() => {
    activeIdRef.current = activeId;
    const term = termRef.current;
    if (!term) return;
    sourceRef.current?.close();
    sourceRef.current = null;
    term.reset();
    if (!activeId) {
      setLink("offline");
      return;
    }
    setLink("connecting");
    setNotice("");
    const source = new EventSource(terminalStreamUrl(activeId));
    sourceRef.current = source;
    source.addEventListener("hello", (event) => {
      setLink("online");
      setNotice("");
      try {
        const info = JSON.parse((event as MessageEvent).data) as WebTerminalInfo;
        term.resize(info.columns, info.rows);
      } catch {
        // Metadata parsing errors do not affect the output stream
      }
    });
    source.addEventListener("output", (event) => {
      const payload = (event as MessageEvent).data as string;
      term.write(base64ToBytes(payload));
    });
    source.addEventListener("exit", () => {
      setLink("closed");
      setNotice("Session ended (shell exited or was reclaimed by the server)");
    });
    source.onerror = () => {
      setLink(source.readyState === EventSource.CLOSED ? "offline" : "connecting");
    };
    return () => {
      source.close();
      if (sourceRef.current === source) sourceRef.current = null;
    };
  }, [activeId]);

  const fitTerminal = useCallback(() => {
    const term = termRef.current;
    const fit = fitRef.current;
    const id = activeIdRef.current;
    if (!term || !fit || !id) return;
    try {
      fit.fit();
      const { cols, rows } = term;
      if (resizeTimerRef.current !== null) window.clearTimeout(resizeTimerRef.current);
      // Debounce resize reports to avoid request storms while resizing the window.
      resizeTimerRef.current = window.setTimeout(() => {
        void resizeTerminal(id, cols, rows).catch(() => undefined);
      }, 150);
    } catch {
      // Ignore fit errors when the container is hidden (display:none).
    }
  }, []);

  useEffect(() => {
    if (!active) return;
    fitTerminal();
    const observer = new ResizeObserver(() => fitTerminal());
    if (containerRef.current) observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [active, fitTerminal]);

  const refresh = useCallback(async () => {
    try {
      const list = await listTerminals();
      setTerminals(list);
      setActiveId((current) =>
        current && list.some((item) => item.id === current)
          ? current
          : list[0]?.id ?? null,
      );
    } catch (error) {
      setNotice(errorMessage(error));
    }
  }, []);

  useEffect(() => {
    if (active) void refresh();
  }, [active, refresh]);

  async function handleCreate() {
    try {
      const term = termRef.current;
      const created = await createTerminal({
        columns: term?.cols ?? 80,
        rows: term?.rows ?? 24,
      });
      setTerminals((current) => [...current, created]);
      setActiveId(created.id);
    } catch (error) {
      setNotice(errorMessage(error));
    }
  }

  async function handleClose(id: string) {
    try {
      await closeTerminal(id);
      const next = terminals.filter((item) => item.id !== id);
      setTerminals(next);
      if (activeId === id) setActiveId(next[0]?.id ?? null);
    } catch (error) {
      setNotice(errorMessage(error));
    }
  }

  const linkLabel: Record<LinkState, string> = {
    offline: "Disconnected",
    connecting: "Connecting",
    online: "Connected",
    closed: "Ended",
  };

  return (
    <section className={`terminal-pane${active ? "" : " hidden"}`} aria-label="Remote terminal">
      <header className="terminal-toolbar">
        <div className="terminal-tabs">
          {terminals.map((item) => (
            <button
              className={`terminal-tab${item.id === activeId ? " active" : ""}`}
              key={item.id}
              title={item.workingDirectory}
              type="button"
              onClick={() => setActiveId(item.id)}
            >
              <Icon name="terminal" size={13} />
              <span>{item.label}</span>
              {!item.alive && <em className="terminal-dead">Exited</em>}
            </button>
          ))}
        </div>
        <div className="terminal-actions">
          <button className="quiet-button" type="button" onClick={() => void handleCreate()}>
            <Icon name="plus" size={14} /><span>New</span>
          </button>
          {activeId && (
            <button className="quiet-button danger" type="button" onClick={() => void handleClose(activeId)}>
              <Icon name="x" size={14} /><span>Close</span>
            </button>
          )}
          <span className={`terminal-link link-${link}`}>{linkLabel[link]}</span>
        </div>
      </header>
      {notice && <div className="terminal-notice" role="status">{notice}</div>}
      <div className="terminal-host" ref={containerRef} />
      {!terminals.length && (
        <div className="terminal-empty">
          <p>还没有Remote terminal会话</p>
          <button className="primary-small-button" type="button" onClick={() => void handleCreate()}>
            <Icon name="plus" size={14} /><span>Start the first terminal</span>
          </button>
        </div>
      )}
    </section>
  );
}

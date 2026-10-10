import { workspaceDownloadUrl } from "../api";
import { formatBytes } from "../format";
import type { WorkspaceItem } from "../types";
import { Icon } from "./Icon";

interface ContextPaneProps {
  workspacePath: string;
  workspaceItems: WorkspaceItem[];
  workspaceFilePath: string | null;
  workspaceContent: string;
  workspaceDirty: boolean;
  onOpenConversations: () => void;
  onWorkspacePath: () => void;
  onWorkspaceItem: (item: WorkspaceItem) => void;
  onWorkspaceRefresh: () => void;
  onWorkspaceContent: (content: string) => void;
  onWorkspaceSave: () => void;
  onWorkspaceUpload: (files: FileList) => void;
  onWorkspaceCreate: (kind: "file" | "dir") => void;
  onWorkspaceRename: (item: WorkspaceItem) => void;
  onWorkspaceDelete: (item: WorkspaceItem) => void;
}

export function ContextPane({
  workspacePath,
  workspaceItems,
  workspaceFilePath,
  workspaceContent,
  workspaceDirty,
  onOpenConversations,
  onWorkspacePath,
  onWorkspaceItem,
  onWorkspaceRefresh,
  onWorkspaceContent,
  onWorkspaceSave,
  onWorkspaceUpload,
  onWorkspaceCreate,
  onWorkspaceRename,
  onWorkspaceDelete,
}: ContextPaneProps) {
  return (
    <aside className="context-pane">
      <div className="mobile-context-header">
        <button className="appbar-icon" type="button" aria-label="Open conversation list" onClick={onOpenConversations}>
          <Icon name="menu" size={20} />
        </button>
        <strong>Linux workspace</strong>
        <span />
      </div>

      <section id="workspace-panel" className="context-panel active">
        <header className="context-header">
          <div>
            <strong>Linux workspace</strong>
            <button className="path-button" type="button" title={workspacePath} onClick={onWorkspacePath}>
              {workspacePath || "/workspace"}
            </button>
          </div>
          <div className="header-actions">
            <label className="quiet-button" title="Upload a file to the current directory">
              <Icon name="upload" size={14} /><span>Upload</span>
              <input
                type="file"
                multiple
                hidden
                onChange={(event) => {
                  if (event.target.files?.length) onWorkspaceUpload(event.target.files);
                  event.target.value = "";
                }}
              />
            </label>
            <button className="quiet-button" type="button" title="Create file" onClick={() => onWorkspaceCreate("file")}>
              <Icon name="file" size={14} />
            </button>
            <button className="quiet-button" type="button" title="Create file夹" onClick={() => onWorkspaceCreate("dir")}>
              <Icon name="folder" size={14} />
            </button>
            {workspaceFilePath && (
              <a className="quiet-link" href={workspaceDownloadUrl(workspaceFilePath)} title="Download file">
                <Icon name="download" size={15} /><span>Download</span>
              </a>
            )}
            <button className="quiet-button" type="button" onClick={onWorkspaceRefresh}>
              <Icon name="refresh" size={14} /><span>Refresh</span>
            </button>
            <button
              className="primary-small-button"
              type="button"
              disabled={!workspaceDirty || !workspaceFilePath}
              onClick={onWorkspaceSave}
            ><Icon name="save" size={14} /><span>Save</span></button>
          </div>
        </header>
        <div className="workspace-layout">
          <div className="workspace-list">
            {!workspaceItems.length && <div className="list-empty">The current workspace is empty</div>}
            {workspaceItems.map((item) => (
              <div className={`workspace-item-row${item.path === workspaceFilePath ? " active" : ""}`} key={item.path}>
                <button className="workspace-item" type="button" onClick={() => onWorkspaceItem(item)}>
                  <Icon name={item.isDirectory ? "folder" : "file"} size={15} />
                  <span>{item.name}</span>
                  <small>{item.isDirectory ? "" : formatBytes(item.size)}</small>
                </button>
                <button className="item-action" type="button" title="Rename" onClick={() => onWorkspaceRename(item)}>
                  <Icon name="pencil" size={13} />
                </button>
                <button className="item-action danger" type="button" title="Delete" onClick={() => onWorkspaceDelete(item)}>
                  <Icon name="trash" size={13} />
                </button>
              </div>
            ))}
          </div>
          <div className="workspace-editor-wrap">
            <p>{workspaceFilePath || "Select a file to view or edit"}</p>
            <textarea
              id="workspace-editor"
              spellCheck={false}
              disabled={!workspaceFilePath}
              value={workspaceContent}
              onChange={(event) => onWorkspaceContent(event.target.value)}
            />
          </div>
        </div>
      </section>
    </aside>
  );
}

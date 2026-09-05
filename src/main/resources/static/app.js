import {
  consumeSse,
  formatBytes,
  formatScore,
  isActiveJob,
  parseEventJson,
  progressPercent,
  statusLabel
} from "/ui-core.mjs?v=2.0.0-rc1.20260720-4";

const STORAGE = {
  space: "rag-study-helper:selected-space",
  view: "rag-study-helper:selected-view",
  theme: "rag-study-helper:theme"
};
const SESSION_API_KEY = "rag-study-helper:api-key";
const CHUNK_PAGE_SIZE = 30;
const WRITE_OFFLINE_MESSAGE = "当前离线，网络恢复后再执行此操作。";

const state = {
  spaces: [],
  sessions: [],
  documents: [],
  jobs: [],
  syncRuns: [],
  currentSpaceId: null,
  currentSessionId: null,
  activeView: "chat",
  health: null,
  readiness: null,
  vector: null,
  feishu: null,
  stream: null,
  upload: null,
  chunkContext: null,
  drawerRestoreFocus: null,
  polling: false,
  pollingSuspended: false,
  pollAbortController: null,
  pollCount: 0
};

const dom = Object.fromEntries([
  "offlineBanner", "navigationRail", "railScrim", "openRailButton", "closeRailButton",
  "spaceList", "newSpaceButton", "sessionList", "newSessionButton", "settingsButton",
  "runtimeDot", "runtimeText", "currentSpaceName", "currentSpaceDescription", "providerBadge",
  "themeButton", "viewTabs", "chatView", "libraryView", "operationsView", "chatScroll",
  "chatEmpty", "starterPrompts", "messageList", "streamStatus", "streamStatusText",
  "cancelStreamButton", "chatForm", "questionInput", "questionCount", "sendButton",
  "fileInput", "uploadLabel", "scanButton", "refreshLibraryButton", "uploadProgress",
  "uploadProgressLabel", "uploadProgressValue", "uploadProgressBar", "cancelUploadButton",
  "libraryError", "libraryErrorText", "retryLibraryButton", "documentGrid", "documentEmpty",
  "documentCountBadge", "activeJobBadge", "refreshOperationsButton", "dependencyGrid",
  "jobSummary", "jobList", "jobEmpty", "feishuMode", "feishuDescription",
  "syncFeishuButton", "reconcileVectorButton", "rebuildVectorButton", "syncRunList",
  "chunkDrawer", "chunkDrawerEyebrow", "chunkDrawerTitle", "chunkDrawerMeta", "chunkDrawerBody",
  "loadMoreChunksButton", "closeChunkDrawerButton", "drawerScrim", "spaceDialog", "spaceForm",
  "spaceDialogTitle", "spaceIdInput", "spaceNameInput", "spaceDescriptionInput",
  "spaceFormError", "saveSpaceButton", "settingsDialog", "settingsForm", "apiKeyInput",
  "showApiKeyInput", "clearApiKeyButton", "confirmDialog", "confirmTitle", "confirmMessage",
  "confirmCancelButton", "confirmAcceptButton", "toastRegion"
].map((id) => [id, document.getElementById(id)]));

class ApiError extends Error {
  constructor(message, status = 0, envelope = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.envelope = envelope;
  }
}

function node(tag, className, text) {
  const value = document.createElement(tag);
  if (className) value.className = className;
  if (text !== undefined && text !== null) value.textContent = String(text);
  return value;
}

function stored(storage, key, fallback = "") {
  try {
    return storage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

function store(storage, key, value) {
  try {
    if (value === null || value === undefined || value === "") storage.removeItem(key);
    else storage.setItem(key, String(value));
  } catch {
    // Storage may be unavailable in hardened/private browser contexts.
  }
}

function apiKey() {
  return stored(sessionStorage, SESSION_API_KEY).trim();
}

function requestHeaders(extra = {}) {
  const headers = new Headers(extra);
  if (!headers.has("Accept")) headers.set("Accept", "application/json");
  const key = apiKey();
  if (key) headers.set("X-API-Key", key);
  return headers;
}

async function api(path, options = {}) {
  const headers = requestHeaders(options.headers);
  let body = options.body;
  if (body !== undefined && body !== null && !(body instanceof FormData) && typeof body !== "string") {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(body);
  }
  const response = await fetch(path, { ...options, cache: "no-store", headers, body });
  const envelope = await responseEnvelope(response);
  if (!response.ok || envelope?.resCode !== "200") {
    const message = envelope?.msg || `请求失败（HTTP ${response.status}）`;
    if (response.status === 401 || response.status === 403) offerCredentials();
    throw new ApiError(message, response.status, envelope);
  }
  return envelope.obj;
}

async function responseEnvelope(response) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    throw new ApiError("服务返回了无法识别的数据。", response.status);
  }
}

async function inspectEndpoint(path) {
  try {
    const response = await fetch(path, { headers: requestHeaders() });
    const envelope = await responseEnvelope(response);
    return { ok: response.ok && envelope?.resCode === "200", status: response.status, envelope };
  } catch (error) {
    return { ok: false, status: 0, envelope: null, error };
  }
}

function notify(message, kind = "info", timeout = 4200) {
  const toast = node("div", `toast ${kind}`, message);
  toast.setAttribute("role", kind === "error" ? "alert" : "status");
  dom.toastRegion.append(toast);
  window.setTimeout(() => toast.remove(), timeout);
}

function readableError(error) {
  if (error instanceof ApiError) return error.message;
  if (error?.name === "AbortError") return "请求已取消";
  return error?.message || "操作失败，请稍后重试。";
}

function ensureWritable() {
  if (!navigator.onLine) {
    notify(WRITE_OFFLINE_MESSAGE, "warning");
    return false;
  }
  return true;
}

function formatDate(value) {
  if (!value) return "时间未知";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
  }).format(date);
}

function formatLocation(item) {
  const parts = [];
  if (item.sectionTitle) parts.push(item.sectionTitle);
  if (item.pageNumber !== null && item.pageNumber !== undefined) parts.push(`第 ${item.pageNumber} 页`);
  if (item.chunkIndex !== null && item.chunkIndex !== undefined) parts.push(`分块 ${item.chunkIndex + 1}`);
  if (item.startOffset !== null && item.startOffset !== undefined
      && item.endOffset !== null && item.endOffset !== undefined) {
    parts.push(`字符 ${item.startOffset}–${item.endOffset}`);
  }
  return parts.join(" · ") || "精确分块";
}

function statusClass(status) {
  return String(status || "pending").toLowerCase().replace(/[^a-z0-9_-]/g, "");
}

function setTheme(theme) {
  const normalized = theme === "dark" ? "dark" : "light";
  document.documentElement.dataset.theme = normalized;
  document.querySelector('meta[name="theme-color"]')?.setAttribute(
    "content", normalized === "dark" ? "#181b18" : "#f3f0e7"
  );
  dom.themeButton.setAttribute("aria-label", normalized === "dark" ? "切换浅色模式" : "切换深色模式");
  dom.themeButton.title = dom.themeButton.getAttribute("aria-label");
  store(localStorage, STORAGE.theme, normalized);
}

function updateOnlineState() {
  dom.offlineBanner.hidden = navigator.onLine;
  document.body.classList.toggle("offline", !navigator.onLine);
}

function openRail() {
  dom.navigationRail.classList.add("open");
  dom.railScrim.hidden = false;
  document.body.classList.add("modal-open");
}

function closeRail() {
  dom.navigationRail.classList.remove("open");
  dom.railScrim.hidden = true;
  if (!dom.chunkDrawer.classList.contains("open")) document.body.classList.remove("modal-open");
}

function switchView(view, { persist = true } = {}) {
  if (state.stream && view !== "chat") {
    notify("请先停止当前回答，再切换页面。", "warning");
    return;
  }
  const valid = ["chat", "library", "operations"].includes(view) ? view : "chat";
  state.activeView = valid;
  for (const button of dom.viewTabs.querySelectorAll("[data-view]")) {
    const active = button.dataset.view === valid;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
    button.tabIndex = active ? 0 : -1;
  }
  for (const [name, panel] of [["chat", dom.chatView], ["library", dom.libraryView], ["operations", dom.operationsView]]) {
    const active = name === valid;
    panel.hidden = !active;
    panel.classList.toggle("active", active);
  }
  if (persist) store(localStorage, STORAGE.view, valid);
  if (valid === "operations") void loadOperations();
}

function setNavigationLocked(locked) {
  dom.newSpaceButton.disabled = locked;
  dom.newSessionButton.disabled = locked;
  for (const button of dom.viewTabs.querySelectorAll("button")) button.disabled = locked;
  for (const button of document.querySelectorAll(".rail-item-main, .rail-row-actions button")) button.disabled = locked;
}

async function checkRuntime() {
  const [healthResult, readinessResult] = await Promise.all([
    inspectEndpoint("/api/health"), inspectEndpoint("/api/readiness")
  ]);
  state.health = healthResult.envelope?.obj || null;
  state.readiness = readinessResult.envelope?.obj || null;

  const ready = readinessResult.ok && state.readiness?.status === "UP";
  dom.runtimeDot.className = `status-dot ${ready ? "ready" : "error"}`;
  dom.runtimeText.textContent = ready ? "服务与依赖均已就绪" : "存在未就绪依赖";

  const provider = state.health?.ragProvider;
  dom.providerBadge.className = `mode-badge ${healthResult.ok ? "ready" : "error"}`;
  dom.providerBadge.textContent = provider === "mock" ? "Mock 评估模式" : provider === "openai" ? "外部模型模式" : "服务不可用";
  renderDependencies();
}

function renderDependencies() {
  dom.dependencyGrid.replaceChildren();
  const components = state.readiness?.components || {};
  const vectorComponent = components.vector || {};
  const cards = [
    ["应用服务", state.health?.status || "DOWN", state.health?.service || "rag-study-helper"],
    ["MySQL", components.mysql || "UNKNOWN", "活跃文档真源"],
    ["Redis", components.redis || "UNKNOWN", "会话、限流与分布式锁"],
    ["向量索引", vectorComponent.status || state.vector?.status || "UNKNOWN",
      `${vectorComponent.type || state.vector?.storeType || "—"} · ${vectorComponent.indexedChunks ?? state.vector?.indexedChunks ?? 0} 条`],
    ["模型", components.model || state.health?.ragProvider || "UNKNOWN", "查询改写、重排与流式回答"],
    ["飞书同步", state.feishu?.enabled ? "READY" : "DISABLED", state.feishu?.enabled ? "增量同步已配置" : "当前未启用"]
  ];
  for (const [label, value, note] of cards) {
    const card = node("div", "dependency-item");
    card.append(node("span", "dependency-label", label));
    const valueNode = node("strong", "dependency-value", value);
    valueNode.classList.toggle("dependency-down", ["DOWN", "UNKNOWN", "FAILED"].includes(String(value).toUpperCase()));
    card.append(valueNode, node("span", "dependency-note", note));
    dom.dependencyGrid.append(card);
  }
}

async function loadSpaces(preferredId = null) {
  try {
    state.spaces = await api("/api/spaces") || [];
    const storedId = Number(preferredId ?? stored(localStorage, STORAGE.space, ""));
    const candidate = state.spaces.find((space) => Number(space.id) === storedId) || state.spaces[0] || null;
    if (!candidate) {
      state.currentSpaceId = null;
      state.currentSessionId = null;
      renderSpaces();
      renderCurrentSpace();
      renderSessions();
      renderDocuments();
      renderMessages([]);
      openSpaceDialog();
      return;
    }
    await selectSpace(Number(candidate.id), { force: true });
  } catch (error) {
    dom.spaceList.replaceChildren(node("div", "rail-empty", readableError(error)));
    dom.currentSpaceName.textContent = "无法载入知识空间";
    notify(readableError(error), "error");
  }
}

async function selectSpace(spaceId, { force = false } = {}) {
  if (state.stream) {
    notify("请先停止当前回答，再切换知识空间。", "warning");
    return;
  }
  if (!force && state.currentSpaceId === spaceId) {
    closeRail();
    return;
  }
  state.currentSpaceId = Number(spaceId);
  state.currentSessionId = null;
  store(localStorage, STORAGE.space, state.currentSpaceId);
  renderSpaces();
  renderCurrentSpace();
  renderMessages([]);
  closeDrawer();
  closeRail();
  await Promise.allSettled([loadSessions(), loadDocuments(), loadJobs(), loadFeishuStatus(), loadVectorStatus()]);
}

function renderCurrentSpace() {
  const current = state.spaces.find((space) => Number(space.id) === state.currentSpaceId);
  dom.currentSpaceName.textContent = current?.name || "尚未选择知识空间";
  dom.currentSpaceDescription.textContent = current?.description || "每个空间拥有独立资料、向量和会话。";
  dom.questionInput.disabled = !current || Boolean(state.stream);
  dom.sendButton.disabled = !current || Boolean(state.stream);
}

function renderSpaces() {
  dom.spaceList.replaceChildren();
  if (!state.spaces.length) {
    dom.spaceList.append(node("div", "rail-empty", "暂无知识空间"));
    return;
  }
  for (const space of state.spaces) {
    const row = node("div", `rail-item${Number(space.id) === state.currentSpaceId ? " active" : ""}`);
    const main = node("button", "rail-item-main");
    main.type = "button";
    main.disabled = Boolean(state.stream);
    main.setAttribute("aria-current", Number(space.id) === state.currentSpaceId ? "page" : "false");
    const copy = node("span", "rail-item-copy");
    copy.append(node("span", "rail-item-title", space.name), node("span", "rail-item-description", space.description || `空间 #${space.id}`));
    main.append(copy);
    main.addEventListener("click", () => void selectSpace(Number(space.id)));

    const actions = node("span", "rail-row-actions");
    const edit = node("button", "", "✎");
    edit.type = "button";
    edit.title = `编辑 ${space.name}`;
    edit.setAttribute("aria-label", edit.title);
    edit.disabled = Boolean(state.stream);
    edit.addEventListener("click", () => openSpaceDialog(space));
    const remove = node("button", "", "×");
    remove.type = "button";
    remove.title = `删除 ${space.name}`;
    remove.setAttribute("aria-label", remove.title);
    remove.disabled = Boolean(state.stream);
    remove.addEventListener("click", () => void deleteSpace(space));
    actions.append(edit, remove);
    row.append(main, actions);
    dom.spaceList.append(row);
  }
}

function openSpaceDialog(space = null) {
  if (state.stream) return;
  dom.spaceDialogTitle.textContent = space ? "编辑知识空间" : "新建知识空间";
  dom.spaceIdInput.value = space?.id ?? "";
  dom.spaceNameInput.value = space?.name ?? "";
  dom.spaceDescriptionInput.value = space?.description ?? "";
  dom.spaceFormError.hidden = true;
  dom.spaceDialog.showModal();
  dom.spaceNameInput.focus();
}

async function saveSpace(event) {
  event.preventDefault();
  if (!ensureWritable()) return;
  const id = dom.spaceIdInput.value;
  const name = dom.spaceNameInput.value.trim();
  const description = dom.spaceDescriptionInput.value.trim();
  if (!name) {
    dom.spaceFormError.textContent = "请输入空间名称。";
    dom.spaceFormError.hidden = false;
    return;
  }
  dom.saveSpaceButton.disabled = true;
  dom.spaceFormError.hidden = true;
  try {
    const saved = await api(id ? `/api/spaces/${id}` : "/api/spaces", {
      method: id ? "PUT" : "POST", body: { name, description }
    });
    dom.spaceDialog.close();
    notify(id ? "知识空间已更新。" : "知识空间已创建。");
    await loadSpaces(saved?.id ?? id);
  } catch (error) {
    dom.spaceFormError.textContent = readableError(error);
    dom.spaceFormError.hidden = false;
  } finally {
    dom.saveSpaceButton.disabled = false;
  }
}

async function deleteSpace(space) {
  if (!ensureWritable()) return;
  const accepted = await confirmAction("删除知识空间", `将删除“${space.name}”及其资料、会话和索引状态。此操作不可撤销。`);
  if (!accepted) return;
  const deletingCurrentSpace = Number(space.id) === state.currentSpaceId;
  if (deletingCurrentSpace) {
    state.pollingSuspended = true;
    state.pollAbortController?.abort();
  }
  try {
    await api(`/api/spaces/${space.id}`, { method: "DELETE" });
    notify("知识空间已删除。");
    await loadSpaces();
  } catch (error) {
    notify(readableError(error), "error");
  } finally {
    if (deletingCurrentSpace) state.pollingSuspended = false;
  }
}

async function loadSessions({ preserveMessages = false } = {}) {
  if (!state.currentSpaceId) return;
  try {
    state.sessions = await api(`/api/spaces/${state.currentSpaceId}/sessions`) || [];
    renderSessions();
    if (preserveMessages) return;
    const selected = state.sessions.find((session) => session.id === state.currentSessionId) || state.sessions[0];
    if (selected) await selectSession(selected.id, { force: true });
    else {
      state.currentSessionId = null;
      renderMessages([]);
    }
  } catch (error) {
    dom.sessionList.replaceChildren(node("div", "rail-empty", readableError(error)));
  }
}

function renderSessions() {
  dom.sessionList.replaceChildren();
  if (!state.sessions.length) {
    dom.sessionList.append(node("div", "rail-empty", "提问后会自动建立会话"));
    return;
  }
  for (const session of state.sessions) {
    const row = node("div", `rail-item${session.id === state.currentSessionId ? " active" : ""}`);
    const main = node("button", "rail-item-main");
    main.type = "button";
    main.disabled = Boolean(state.stream);
    const copy = node("span", "rail-item-copy");
    copy.append(node("span", "rail-item-title", session.title), node("span", "rail-item-description", formatDate(session.lastMessageAt || session.updateTime)));
    main.append(copy);
    main.addEventListener("click", () => void selectSession(session.id));

    const actions = node("span", "rail-row-actions");
    const rename = node("button", "", "✎");
    rename.type = "button";
    rename.title = "重命名会话";
    rename.setAttribute("aria-label", rename.title);
    rename.disabled = Boolean(state.stream);
    rename.addEventListener("click", () => beginSessionRename(session, row));
    const remove = node("button", "", "×");
    remove.type = "button";
    remove.title = "删除会话";
    remove.setAttribute("aria-label", remove.title);
    remove.disabled = Boolean(state.stream);
    remove.addEventListener("click", () => void deleteSession(session));
    actions.append(rename, remove);
    row.append(main, actions);
    dom.sessionList.append(row);
  }
}

function beginSessionRename(session, row) {
  if (state.stream) return;
  const editor = node("div", "rail-edit-row");
  const input = node("input");
  input.value = session.title;
  input.maxLength = 160;
  input.setAttribute("aria-label", "会话名称");
  const save = node("button", "", "✓");
  save.type = "button";
  save.setAttribute("aria-label", "保存名称");
  const cancel = node("button", "", "×");
  cancel.type = "button";
  cancel.setAttribute("aria-label", "取消重命名");
  const commit = async () => {
    const title = input.value.trim();
    if (!title || !ensureWritable()) return;
    save.disabled = true;
    try {
      await api(`/api/spaces/${state.currentSpaceId}/sessions/${encodeURIComponent(session.id)}`, {
        method: "PUT", body: { title }
      });
      await loadSessions({ preserveMessages: true });
    } catch (error) {
      notify(readableError(error), "error");
      save.disabled = false;
    }
  };
  save.addEventListener("click", () => void commit());
  cancel.addEventListener("click", renderSessions);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") { event.preventDefault(); void commit(); }
    if (event.key === "Escape") renderSessions();
  });
  editor.append(input, save, cancel);
  row.replaceWith(editor);
  input.focus();
  input.select();
}

async function selectSession(sessionId, { force = false } = {}) {
  if (state.stream) {
    notify("请先停止当前回答，再切换会话。", "warning");
    return;
  }
  if (!force && state.currentSessionId === sessionId) {
    closeRail();
    return;
  }
  state.currentSessionId = sessionId;
  renderSessions();
  closeRail();
  try {
    const detail = await api(`/api/spaces/${state.currentSpaceId}/sessions/${encodeURIComponent(sessionId)}`);
    if (state.currentSessionId !== sessionId) return;
    renderMessages(detail?.messages || detail?.history || []);
  } catch (error) {
    renderMessages([]);
    notify(readableError(error), "error");
  }
}

async function createSession(title = "新会话") {
  if (!state.currentSpaceId || !ensureWritable()) return null;
  const session = await api(`/api/spaces/${state.currentSpaceId}/sessions`, { method: "POST", body: { title } });
  state.currentSessionId = session.id;
  await loadSessions({ preserveMessages: true });
  renderMessages([]);
  return session;
}

async function deleteSession(session) {
  if (!ensureWritable()) return;
  const accepted = await confirmAction("删除会话", `删除“${session.title}”及其对话记录？`);
  if (!accepted) return;
  try {
    await api(`/api/spaces/${state.currentSpaceId}/sessions/${encodeURIComponent(session.id)}`, { method: "DELETE" });
    if (state.currentSessionId === session.id) state.currentSessionId = null;
    await loadSessions();
    notify("会话已删除。");
  } catch (error) {
    notify(readableError(error), "error");
  }
}

function renderMessages(messages) {
  dom.messageList.replaceChildren();
  for (const message of messages) appendMessage(message.role, message.content);
  dom.chatEmpty.hidden = messages.length > 0;
  scrollChat(false);
}

function appendMessage(role, content = "") {
  const normalized = String(role || "assistant").toLowerCase() === "user" ? "user" : "assistant";
  const root = node("article", `message ${normalized}`);
  const roleNode = node("div", "message-role", normalized === "user" ? "YOU" : "RAG");
  const contentNode = node("div", "message-content", content);
  const citations = node("div", "citation-list");
  root.append(roleNode, contentNode, citations);
  dom.messageList.append(root);
  dom.chatEmpty.hidden = true;
  return { root, content: contentNode, citations };
}

function renderCitations(container, snippets, capturedSpaceId) {
  container.replaceChildren();
  snippets.forEach((snippet, index) => {
    const button = node("button", "citation-button");
    button.type = "button";
    button.append(node("span", "citation-number", String(index + 1).padStart(2, "0")));
    const copy = node("span", "citation-copy");
    copy.append(node("span", "citation-title", snippet.documentName || `文档 #${snippet.documentId}`),
      node("span", "citation-location", formatLocation(snippet)));
    button.append(copy, node("span", "citation-score", formatScore(snippet.rerankScore ?? snippet.retrievalScore)));
    button.addEventListener("click", () => void openCitation(capturedSpaceId, snippet));
    container.append(button);
  });
}

function scrollChat(smooth = true) {
  window.requestAnimationFrame(() => {
    dom.chatScroll.scrollTo({ top: dom.chatScroll.scrollHeight, behavior: smooth ? "smooth" : "auto" });
  });
}

async function submitChat(question) {
  const value = String(question ?? dom.questionInput.value).trim();
  if (!value || state.stream || !state.currentSpaceId || !ensureWritable()) return;
  if (!state.currentSessionId) {
    try {
      await createSession(value.slice(0, 80));
    } catch (error) {
      notify(readableError(error), "error");
      return;
    }
  }

  const capturedSpaceId = state.currentSpaceId;
  const capturedSessionId = state.currentSessionId;
  dom.questionInput.value = "";
  resizeQuestion();
  updateQuestionCount();
  appendMessage("user", value);
  const answer = appendMessage("assistant", "");
  scrollChat();

  const active = {
    spaceId: capturedSpaceId,
    sessionId: capturedSessionId,
    question: value,
    requestId: null,
    controller: new AbortController(),
    answer,
    text: "",
    snippets: [],
    terminal: false,
    cancelRequested: false
  };
  state.stream = active;
  setStreamUi(true, "正在连接检索服务");
  setNavigationLocked(true);
  renderCurrentSpace();

  let outcome = "error";
  try {
    const headers = requestHeaders({ "Content-Type": "application/json", "Accept": "text/event-stream" });
    const response = await fetch(`/api/spaces/${capturedSpaceId}/chat`, {
      method: "POST",
      headers,
      body: JSON.stringify({ sessionId: capturedSessionId, question: value }),
      signal: active.controller.signal
    });
    if (!response.ok || !response.body) {
      const envelope = await responseEnvelope(response);
      throw new ApiError(envelope?.msg || `问答请求失败（HTTP ${response.status}）`, response.status, envelope);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!active.terminal) {
      const { value: bytes, done } = await reader.read();
      const decoded = decoder.decode(bytes || new Uint8Array(), { stream: !done });
      const parsed = consumeSse(buffer, decoded);
      buffer = parsed.rest;
      for (const event of parsed.events) {
        outcome = handleStreamEvent(active, event) || outcome;
        if (active.terminal) break;
      }
      if (done) {
        if (buffer.trim() && !active.terminal) {
          const final = consumeSse(buffer, "\n\n");
          for (const event of final.events) outcome = handleStreamEvent(active, event) || outcome;
        }
        break;
      }
    }
    if (!active.terminal) throw new Error("流式连接提前结束，回答可能不完整。");
  } catch (error) {
    if (active.cancelRequested || error?.name === "AbortError") {
      outcome = "cancelled";
    } else {
      outcome = "error";
      active.answer.root.classList.add("error");
      if (!active.text) active.answer.content.textContent = readableError(error);
      notify(readableError(error), "error");
    }
  } finally {
    finalizeStream(active, outcome);
  }
}

function handleStreamEvent(active, event) {
  if (state.stream !== active || active.terminal) return null;
  const payload = parseEventJson(event.data);
  if (!payload) throw new Error("收到无法解析的流式事件。");
  if (active.requestId && payload.requestId && payload.requestId !== active.requestId) return null;
  if (payload.requestId && !active.requestId) active.requestId = payload.requestId;

  if (event.event === "status") {
    const labels = { retrieving: "正在检索资料", rewriting: "正在改写查询", generating: "正在组织带引用回答" };
    setStreamUi(true, labels[payload.state] || "正在处理问题");
  } else if (event.event === "retrieval") {
    active.snippets = Array.isArray(payload.snippets) ? payload.snippets : [];
    renderCitations(active.answer.citations, active.snippets, active.spaceId);
    setStreamUi(true, active.snippets.length ? `已找到 ${active.snippets.length} 条依据，正在生成回答` : "未检索到可信依据");
  } else if (event.event === "token") {
    active.text += String(payload.token ?? "");
    active.answer.content.textContent = active.text;
    scrollChat();
  } else if (event.event === "done") {
    active.terminal = true;
    return "done";
  } else if (event.event === "cancelled") {
    active.terminal = true;
    return "cancelled";
  } else if (event.event === "error") {
    active.terminal = true;
    active.answer.root.classList.add("error");
    const message = payload.error?.msg || "回答生成失败。";
    if (!active.text) active.answer.content.textContent = message;
    notify(message, "error");
    return "error";
  }
  return null;
}

function finalizeStream(active, outcome) {
  if (state.stream !== active) return;
  if (outcome === "cancelled") {
    if (!active.text) active.answer.content.textContent = "已停止生成。";
    const meta = node("div", "message-meta", "本次回答已停止，不会被标记为完成。");
    active.answer.root.append(meta);
    addRetryAction(active, "重新生成");
  } else if (outcome === "done") {
    const meta = node("div", "message-meta", `${active.snippets.length} 条检索依据 · 回答已完成`);
    active.answer.root.append(meta);
  } else {
    active.answer.root.classList.add("error");
    if (!active.answer.content.textContent) active.answer.content.textContent = "连接中断，未收到完整回答。";
    addRetryAction(active, "重试此问题");
  }
  state.stream = null;
  setStreamUi(false);
  setNavigationLocked(false);
  renderCurrentSpace();
  renderSpaces();
  renderSessions();
  void loadSessions({ preserveMessages: true });
  scrollChat();
}

function addRetryAction(active, label) {
  const actions = node("div", "message-actions");
  const retry = node("button", "link-button", label);
  retry.type = "button";
  retry.addEventListener("click", () => {
    if (state.stream) return;
    retry.disabled = true;
    void submitChat(active.question);
  });
  actions.append(retry);
  active.answer.root.append(actions);
}

async function cancelStream() {
  const active = state.stream;
  if (!active || active.terminal || active.cancelRequested) return;
  active.cancelRequested = true;
  setStreamUi(true, "正在停止回答");
  try {
    if (active.requestId) {
      await api(`/api/spaces/${active.spaceId}/chat/requests/${encodeURIComponent(active.requestId)}/cancel`, { method: "POST" });
    }
  } catch (error) {
    notify(`停止请求未确认：${readableError(error)}`, "warning");
  } finally {
    active.controller.abort();
  }
}

function setStreamUi(streaming, text = "") {
  dom.streamStatus.hidden = !streaming;
  dom.streamStatusText.textContent = text;
  dom.cancelStreamButton.disabled = !streaming || Boolean(state.stream?.cancelRequested);
  dom.questionInput.disabled = streaming || !state.currentSpaceId;
  dom.sendButton.disabled = streaming || !state.currentSpaceId;
}

function resizeQuestion() {
  dom.questionInput.style.height = "auto";
  dom.questionInput.style.height = `${Math.min(dom.questionInput.scrollHeight, 180)}px`;
}

function updateQuestionCount() {
  dom.questionCount.textContent = `${dom.questionInput.value.length} / 2000`;
}

async function loadDocuments() {
  if (!state.currentSpaceId) return;
  dom.libraryError.hidden = true;
  try {
    state.documents = await api(`/api/spaces/${state.currentSpaceId}/documents`) || [];
    renderDocuments();
  } catch (error) {
    dom.libraryErrorText.textContent = readableError(error);
    dom.libraryError.hidden = false;
    state.documents = [];
    renderDocuments();
  }
}

function renderDocuments() {
  dom.documentGrid.replaceChildren();
  dom.documentCountBadge.textContent = String(state.documents.length);
  dom.documentEmpty.hidden = state.documents.length > 0;
  dom.documentGrid.hidden = state.documents.length === 0;
  state.documents.forEach((documentInfo, index) => {
    const card = node("article", "document-card");
    const head = node("div", "document-card-head");
    head.append(node("span", "document-index", String(index + 1).padStart(2, "0")), node("span", "status-badge ready", "已索引"));
    const title = node("h3", "", documentInfo.documentName);
    title.title = documentInfo.documentName;
    const extension = documentInfo.documentName?.includes(".") ? documentInfo.documentName.split(".").pop().toUpperCase() : "文档";
    const meta = node("div", "document-meta");
    meta.append(node("span", "", extension), node("span", "", `${documentInfo.chunks} 个分块`), node("span", "", `文档 #${documentInfo.id}`));
    const actions = node("div", "document-actions");
    const preview = node("button", "link-button", "检查分块");
    preview.type = "button";
    preview.addEventListener("click", () => void openDocumentChunks(documentInfo));
    const remove = node("button", "link-button danger", "删除");
    remove.type = "button";
    remove.addEventListener("click", () => void deleteDocument(documentInfo));
    actions.append(preview, remove);
    card.append(head, title, meta, actions);
    dom.documentGrid.append(card);
  });
}

async function openDocumentChunks(documentInfo) {
  state.chunkContext = { type: "document", document: documentInfo, offset: 0, hasMore: false, loading: false };
  openDrawer();
  dom.chunkDrawerEyebrow.textContent = "分块预览";
  dom.chunkDrawerTitle.textContent = documentInfo.documentName;
  renderDrawerMeta([`${documentInfo.chunks} 个分块`, `文档 #${documentInfo.id}`]);
  dom.chunkDrawerBody.replaceChildren(node("div", "drawer-loading", "正在读取分块…"));
  await loadChunkPage(true);
}

async function loadChunkPage(reset = false) {
  const context = state.chunkContext;
  if (!context || context.type !== "document" || context.loading) return;
  context.loading = true;
  dom.loadMoreChunksButton.disabled = true;
  const offset = reset ? 0 : context.offset;
  try {
    const chunks = await api(`/api/spaces/${state.currentSpaceId}/documents/${context.document.id}/chunks?offset=${offset}&limit=${CHUNK_PAGE_SIZE}`) || [];
    if (state.chunkContext !== context) return;
    if (reset) dom.chunkDrawerBody.replaceChildren();
    for (const chunk of chunks) dom.chunkDrawerBody.append(renderChunk(chunk, context.document));
    context.offset = offset + chunks.length;
    context.hasMore = chunks.length === CHUNK_PAGE_SIZE && context.offset < Number(context.document.chunks || Infinity);
    dom.loadMoreChunksButton.hidden = !context.hasMore;
    if (reset && chunks.length === 0) dom.chunkDrawerBody.append(node("div", "drawer-loading", "这个文档没有可预览分块。"));
  } catch (error) {
    if (reset) dom.chunkDrawerBody.replaceChildren();
    dom.chunkDrawerBody.append(node("div", "drawer-loading", readableError(error)));
  } finally {
    context.loading = false;
    dom.loadMoreChunksButton.disabled = false;
  }
}

function renderChunk(chunk, documentInfo, exact = false) {
  const item = node("article", `chunk-item${exact ? " highlight" : ""}`);
  const head = node("div", "chunk-head");
  head.append(node("span", "chunk-label", `#${String((chunk.chunkIndex ?? 0) + 1).padStart(2, "0")}`),
    node("span", "chunk-location", formatLocation(chunk)));
  const text = node("p", "chunk-text", chunk.text ?? chunk.preview ?? "");
  item.append(head, text);
  if (!exact && chunk.chunkId) {
    const detail = node("button", "link-button", "查看完整分块");
    detail.type = "button";
    detail.addEventListener("click", () => void openCitation(state.currentSpaceId, {
      documentId: documentInfo.id,
      chunkId: chunk.chunkId,
      documentName: documentInfo.documentName,
      chunkIndex: chunk.chunkIndex
    }));
    item.append(detail);
  }
  return item;
}

async function openCitation(spaceId, snippet) {
  if (!snippet.documentId || !snippet.chunkId) {
    notify("该引用缺少精确分块标识，无法定位。", "warning");
    return;
  }
  state.chunkContext = { type: "citation", spaceId, snippet };
  openDrawer();
  dom.chunkDrawerEyebrow.textContent = "引用定位";
  dom.chunkDrawerTitle.textContent = snippet.documentName || `文档 #${snippet.documentId}`;
  renderDrawerMeta([formatLocation(snippet), `文档 #${snippet.documentId}`, `分块 #${snippet.chunkId}`]);
  dom.chunkDrawerBody.replaceChildren(node("div", "drawer-loading", "正在定位原始分块…"));
  dom.loadMoreChunksButton.hidden = true;
  try {
    const detail = await api(`/api/spaces/${spaceId}/documents/${snippet.documentId}/chunks/${snippet.chunkId}`);
    if (state.chunkContext?.snippet !== snippet) return;
    dom.chunkDrawerBody.replaceChildren(renderChunk(detail, { id: snippet.documentId, documentName: snippet.documentName }, true));
  } catch (error) {
    dom.chunkDrawerBody.replaceChildren(node("div", "drawer-loading", readableError(error)));
  }
}

function renderDrawerMeta(values) {
  dom.chunkDrawerMeta.replaceChildren(...values.filter(Boolean).map((value) => node("span", "", value)));
}

function openDrawer() {
  state.drawerRestoreFocus = document.activeElement;
  dom.chunkDrawer.inert = false;
  dom.drawerScrim.hidden = false;
  dom.chunkDrawer.setAttribute("aria-hidden", "false");
  document.body.classList.add("modal-open");
  window.requestAnimationFrame(() => {
    dom.chunkDrawer.classList.add("open");
    dom.closeChunkDrawerButton.focus();
  });
}

function closeDrawer() {
  dom.chunkDrawer.classList.remove("open");
  dom.chunkDrawer.setAttribute("aria-hidden", "true");
  dom.chunkDrawer.inert = true;
  dom.drawerScrim.hidden = true;
  dom.loadMoreChunksButton.hidden = true;
  state.chunkContext = null;
  if (!dom.navigationRail.classList.contains("open")) document.body.classList.remove("modal-open");
  if (state.drawerRestoreFocus instanceof HTMLElement) state.drawerRestoreFocus.focus();
  state.drawerRestoreFocus = null;
}

async function deleteDocument(documentInfo) {
  if (!ensureWritable()) return;
  const accepted = await confirmAction("删除资料", `“${documentInfo.documentName}”将通过可恢复任务删除，数据库与向量索引会一起清理。`);
  if (!accepted) return;
  try {
    await api(`/api/spaces/${state.currentSpaceId}/documents/${documentInfo.id}`, {
      method: "DELETE", headers: { "Idempotency-Key": crypto.randomUUID() }
    });
    notify("删除任务已进入队列。请在“任务与同步”查看进度。")
    await loadJobs();
    switchView("operations");
  } catch (error) {
    notify(readableError(error), "error");
  }
}

function uploadDocument(file) {
  if (!file || !state.currentSpaceId || !ensureWritable() || state.upload) return;
  if (file.size > 50 * 1024 * 1024) {
    notify("文件超过 50 MB 上限。", "error");
    dom.fileInput.value = "";
    return;
  }
  const xhr = new XMLHttpRequest();
  const form = new FormData();
  form.append("file", file);
  const upload = { xhr, file, cancelled: false };
  state.upload = upload;
  dom.uploadProgress.hidden = false;
  dom.uploadProgressLabel.textContent = `正在上传 ${file.name}`;
  updateUploadProgress(0);
  dom.uploadLabel.setAttribute("aria-disabled", "true");

  xhr.open("POST", `/api/spaces/${state.currentSpaceId}/documents/upload`);
  xhr.setRequestHeader("Accept", "application/json");
  xhr.setRequestHeader("Idempotency-Key", crypto.randomUUID());
  const key = apiKey();
  if (key) xhr.setRequestHeader("X-API-Key", key);
  xhr.upload.addEventListener("progress", (event) => {
    if (event.lengthComputable) updateUploadProgress(progressPercent(event.loaded, event.total));
  });
  xhr.addEventListener("load", async () => {
    let envelope = null;
    try { envelope = JSON.parse(xhr.responseText); } catch { /* handled below */ }
    if (xhr.status >= 200 && xhr.status < 300 && envelope?.resCode === "200") {
      updateUploadProgress(100);
      notify("上传完成，入库任务已进入队列。")
      await loadJobs();
      switchView("operations");
    } else {
      if (xhr.status === 401 || xhr.status === 403) offerCredentials();
      notify(envelope?.msg || `上传失败（HTTP ${xhr.status}）`, "error");
    }
    finishUpload();
  });
  xhr.addEventListener("abort", () => {
    upload.cancelled = true;
    notify("上传已取消。", "warning");
    finishUpload();
  });
  xhr.addEventListener("error", () => {
    notify("上传连接失败。", "error");
    finishUpload();
  });
  xhr.send(form);
}

function updateUploadProgress(percent) {
  const safe = Math.max(0, Math.min(100, Number(percent) || 0));
  dom.uploadProgressValue.textContent = `${safe}%`;
  dom.uploadProgressBar.style.width = `${safe}%`;
}

function finishUpload() {
  state.upload = null;
  dom.fileInput.value = "";
  dom.uploadLabel.removeAttribute("aria-disabled");
  window.setTimeout(() => { if (!state.upload) dom.uploadProgress.hidden = true; }, 650);
}

async function scanDocuments() {
  if (!state.currentSpaceId || !ensureWritable()) return;
  dom.scanButton.disabled = true;
  try {
    const jobs = await api(`/api/spaces/${state.currentSpaceId}/documents/scan`, { method: "POST" }) || [];
    notify(jobs.length ? `已创建 ${jobs.length} 个扫描入库任务。` : "扫描完成，没有发现新增资料。")
    await loadJobs();
    switchView("operations");
  } catch (error) {
    notify(readableError(error), "error");
  } finally {
    dom.scanButton.disabled = false;
  }
}

async function loadJobs({ signal } = {}) {
  const spaceId = state.currentSpaceId;
  if (!spaceId) return;
  const hadActive = state.jobs.some((job) => isActiveJob(job.status));
  try {
    const jobs = await api(`/api/spaces/${spaceId}/jobs`, { signal }) || [];
    if (spaceId !== state.currentSpaceId) return;
    state.jobs = jobs;
    renderJobs();
    const hasActive = state.jobs.some((job) => isActiveJob(job.status));
    if (hadActive && !hasActive) await loadDocuments();
  } catch (error) {
    if (error?.name === "AbortError") return;
    dom.jobList.replaceChildren(node("div", "compact-empty", readableError(error)));
  }
}

function renderJobs() {
  dom.jobList.replaceChildren();
  const activeCount = state.jobs.filter((job) => isActiveJob(job.status)).length;
  dom.jobSummary.textContent = `${state.jobs.length} 条记录`;
  dom.activeJobBadge.textContent = String(activeCount);
  dom.activeJobBadge.hidden = activeCount === 0;
  dom.jobEmpty.hidden = state.jobs.length > 0;
  for (const job of state.jobs.slice(0, 30)) {
    const item = node("article", "job-item");
    const head = node("div", "job-head");
    head.append(node("span", "job-name", job.fileName || `${job.operation} #${job.id}`),
      node("span", `status-badge ${statusClass(job.status)}`, statusLabel(job.status)));
    const meta = node("div", "job-meta");
    meta.append(node("span", "", operationLabel(job.operation)), node("span", "", `任务 #${job.id}`),
      node("span", "", `尝试 ${job.attempts ?? 0}/${job.maxAttempts ?? 0}`), node("span", "", formatDate(job.updateTime)));
    item.append(head, meta);
    const percent = progressPercent(job.progressCurrent, job.progressTotal, job.status);
    if (isActiveJob(job.status) || percent > 0) {
      const progress = node("div", "job-progress");
      const bar = node("span");
      bar.style.width = `${percent}%`;
      progress.append(bar);
      item.append(progress);
    }
    if (job.errorMessage) item.append(node("p", "job-error", `${job.errorCode || "ERROR"}：${job.errorMessage}`));
    const actions = node("div", "job-actions");
    if (isActiveJob(job.status)) {
      const cancel = node("button", "link-button danger", "取消任务");
      cancel.type = "button";
      cancel.addEventListener("click", () => void mutateJob(job, "cancel"));
      actions.append(cancel);
    }
    if (["FAILED", "CANCELLED", "DEAD"].includes(String(job.status).toUpperCase())) {
      const retry = node("button", "link-button", "重试");
      retry.type = "button";
      retry.addEventListener("click", () => void mutateJob(job, "retry"));
      actions.append(retry);
    }
    if (actions.childElementCount) item.append(actions);
    dom.jobList.append(item);
  }
}

function operationLabel(operation) {
  const labels = { UPLOAD: "文档上传", SCAN: "目录扫描", DELETE: "文档删除", FEISHU: "飞书入库" };
  return labels[String(operation).toUpperCase()] || operation || "入库操作";
}

async function mutateJob(job, action) {
  if (!ensureWritable()) return;
  try {
    await api(`/api/spaces/${state.currentSpaceId}/jobs/${job.id}/${action}`, { method: "POST" });
    notify(action === "cancel" ? "取消请求已记录。" : "任务已重新进入队列。")
    await loadJobs();
  } catch (error) {
    notify(readableError(error), "error");
  }
}

async function loadVectorStatus() {
  try {
    state.vector = await api("/api/vector/status");
  } catch {
    state.vector = null;
  }
  renderDependencies();
}

async function loadFeishuStatus() {
  if (!state.currentSpaceId) return;
  try {
    state.feishu = await api(`/api/spaces/${state.currentSpaceId}/feishu/status`);
    const configuredHere = state.feishu?.enabled && Number(state.feishu.localSpaceId) === state.currentSpaceId;
    dom.feishuMode.className = `mode-badge ${configuredHere ? "ready" : "warning"}`;
    dom.feishuMode.textContent = configuredHere ? "已启用" : "未启用";
    dom.syncFeishuButton.disabled = !configuredHere;
    dom.feishuDescription.textContent = configuredHere
      ? `远端空间 ${state.feishu.remoteSpaceId || "已配置"}。仅完整枚举成功后才进入两次缺失确认与删除阈值判断。`
      : "当前知识空间未配置飞书同步；不会发起远端读取或删除。";
    state.syncRuns = configuredHere ? (await api(`/api/spaces/${state.currentSpaceId}/feishu/runs`) || []) : [];
  } catch (error) {
    state.feishu = null;
    state.syncRuns = [];
    dom.feishuMode.className = "mode-badge error";
    dom.feishuMode.textContent = "状态异常";
    dom.feishuDescription.textContent = readableError(error);
    dom.syncFeishuButton.disabled = true;
  }
  renderSyncRuns();
  renderDependencies();
}

function renderSyncRuns() {
  dom.syncRunList.replaceChildren();
  if (!state.syncRuns.length) {
    dom.syncRunList.append(node("div", "compact-empty", "暂无同步运行记录。"));
    return;
  }
  for (const run of state.syncRuns.slice(0, 10)) {
    const item = node("article", "sync-run-item");
    const head = node("div", "job-head");
    head.append(node("span", "job-name", `同步运行 #${run.id}`),
      node("span", `status-badge ${statusClass(run.status)}`, statusLabel(run.status)));
    const meta = node("div", "sync-run-meta");
    meta.append(node("span", "", `枚举 ${run.enumerationComplete ? "完整" : "不完整"}`),
      node("span", "", `发现 ${run.nodesSeen ?? 0}`), node("span", "", `新增 ${run.nodesCreated ?? 0}`),
      node("span", "", `更新 ${run.nodesUpdated ?? 0}`), node("span", "", `删除 ${run.nodesDeleted ?? 0}`),
      node("span", "", `保护 ${run.deletesProtected ?? 0}`), node("span", "", formatDate(run.finishTime || run.createTime)));
    item.append(head, meta);
    if (run.guardReason) item.append(node("p", "job-error", `删除保护：${run.guardReason}`));
    if (run.errorSummary) item.append(node("p", "job-error", run.errorSummary));
    dom.syncRunList.append(item);
  }
}

async function syncFeishu() {
  if (!ensureWritable()) return;
  dom.syncFeishuButton.disabled = true;
  dom.syncFeishuButton.textContent = "同步中…";
  try {
    const report = await api(`/api/spaces/${state.currentSpaceId}/feishu/sync`, { method: "POST" });
    const message = report.enumerationComplete
      ? `同步完成：新增 ${report.created}，更新 ${report.updated}，删除 ${report.deleted}，保护 ${report.protectedDeletes}。`
      : `同步未完整枚举，已阻止删除：${report.guardReason || report.errorSummary || "远端列表不完整"}`;
    notify(message, report.enumerationComplete ? "info" : "warning", 6500);
    await Promise.all([loadFeishuStatus(), loadDocuments(), loadJobs()]);
  } catch (error) {
    notify(readableError(error), "error");
  } finally {
    dom.syncFeishuButton.textContent = "立即同步";
    dom.syncFeishuButton.disabled = !(state.feishu?.enabled && Number(state.feishu.localSpaceId) === state.currentSpaceId);
  }
}

async function reconcileVector() {
  if (!ensureWritable()) return;
  dom.reconcileVectorButton.disabled = true;
  try {
    const report = await api("/api/vector/reconcile", { method: "POST" });
    notify(report.lockAcquired
      ? `向量对账完成：处理 ${report.selected}，修复 ${report.completed}，重试 ${report.retried}。`
      : "另一个实例正在对账，本次未重复执行。", report.lockAcquired ? "info" : "warning");
    await Promise.all([loadVectorStatus(), checkRuntime()]);
  } catch (error) {
    notify(readableError(error), "error");
  } finally {
    dom.reconcileVectorButton.disabled = false;
  }
}

async function rebuildVector() {
  if (!ensureWritable()) return;
  const accepted = await confirmAction("重建向量索引", "将根据 MySQL 中所有 READY 分块重建当前集合。期间就绪检查可能短暂失败，确定继续？");
  if (!accepted) return;
  dom.rebuildVectorButton.disabled = true;
  try {
    const report = await api("/api/vector/rebuild", { method: "POST" });
    notify(`索引重建完成：${report.indexedChunks}/${report.activeChunks} 条，状态 ${report.status}。`, report.status === "READY" ? "info" : "warning");
    await Promise.all([loadVectorStatus(), checkRuntime()]);
  } catch (error) {
    notify(readableError(error), "error");
  } finally {
    dom.rebuildVectorButton.disabled = false;
  }
}

async function loadOperations() {
  await Promise.allSettled([checkRuntime(), loadJobs(), loadVectorStatus(), loadFeishuStatus()]);
}

async function poll() {
  if (state.polling || state.pollingSuspended || document.hidden || !navigator.onLine || !state.currentSpaceId) return;
  state.polling = true;
  const controller = new AbortController();
  state.pollAbortController = controller;
  try {
    await loadJobs({ signal: controller.signal });
    if (!controller.signal.aborted) {
      state.pollCount += 1;
      if (state.pollCount % 4 === 0) await checkRuntime();
    }
  } finally {
    if (state.pollAbortController === controller) state.pollAbortController = null;
    state.polling = false;
  }
}

function offerCredentials() {
  if (!dom.settingsDialog.open) {
    dom.apiKeyInput.value = apiKey();
    dom.settingsDialog.showModal();
  }
}

async function saveSettings(event) {
  event.preventDefault();
  store(sessionStorage, SESSION_API_KEY, dom.apiKeyInput.value.trim());
  dom.settingsDialog.close();
  notify("访问凭据仅保存在当前标签页。")
  await Promise.allSettled([checkRuntime(), loadSpaces(state.currentSpaceId)]);
}

function confirmAction(title, message) {
  dom.confirmTitle.textContent = title;
  dom.confirmMessage.textContent = message;
  dom.confirmDialog.returnValue = "cancel";
  dom.confirmDialog.showModal();
  return new Promise((resolve) => {
    dom.confirmDialog.addEventListener("close", () => resolve(dom.confirmDialog.returnValue === "confirm"), { once: true });
  });
}

function bindEvents() {
  window.addEventListener("online", () => { updateOnlineState(); void checkRuntime(); });
  window.addEventListener("offline", updateOnlineState);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) void poll(); });
  dom.openRailButton.addEventListener("click", openRail);
  dom.closeRailButton.addEventListener("click", closeRail);
  dom.railScrim.addEventListener("click", closeRail);
  dom.themeButton.addEventListener("click", () => setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));
  dom.viewTabs.addEventListener("click", (event) => {
    const button = event.target.closest("[data-view]");
    if (button) switchView(button.dataset.view);
  });
  dom.viewTabs.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight"].includes(event.key)) return;
    const tabs = [...dom.viewTabs.querySelectorAll("[data-view]")];
    const current = tabs.indexOf(document.activeElement);
    const next = (current + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
    tabs[next].focus();
    switchView(tabs[next].dataset.view);
  });
  dom.newSpaceButton.addEventListener("click", () => openSpaceDialog());
  dom.spaceForm.addEventListener("submit", (event) => void saveSpace(event));
  dom.newSessionButton.addEventListener("click", async () => {
    if (state.stream) return;
    try { await createSession(); } catch (error) { notify(readableError(error), "error"); }
  });
  dom.chatForm.addEventListener("submit", (event) => { event.preventDefault(); void submitChat(); });
  dom.questionInput.addEventListener("input", () => { resizeQuestion(); updateQuestionCount(); });
  dom.questionInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      void submitChat();
    }
  });
  dom.starterPrompts.addEventListener("click", (event) => {
    const button = event.target.closest("[data-question]");
    if (button) void submitChat(button.dataset.question);
  });
  dom.cancelStreamButton.addEventListener("click", () => void cancelStream());
  dom.fileInput.addEventListener("change", () => uploadDocument(dom.fileInput.files?.[0]));
  dom.cancelUploadButton.addEventListener("click", () => state.upload?.xhr.abort());
  dom.scanButton.addEventListener("click", () => void scanDocuments());
  dom.refreshLibraryButton.addEventListener("click", () => void loadDocuments());
  dom.retryLibraryButton.addEventListener("click", () => void loadDocuments());
  dom.refreshOperationsButton.addEventListener("click", () => void loadOperations());
  dom.syncFeishuButton.addEventListener("click", () => void syncFeishu());
  dom.reconcileVectorButton.addEventListener("click", () => void reconcileVector());
  dom.rebuildVectorButton.addEventListener("click", () => void rebuildVector());
  dom.loadMoreChunksButton.addEventListener("click", () => void loadChunkPage(false));
  dom.closeChunkDrawerButton.addEventListener("click", closeDrawer);
  dom.drawerScrim.addEventListener("click", closeDrawer);
  dom.settingsButton.addEventListener("click", () => {
    dom.apiKeyInput.value = apiKey();
    dom.settingsDialog.showModal();
  });
  dom.settingsForm.addEventListener("submit", (event) => void saveSettings(event));
  dom.showApiKeyInput.addEventListener("change", () => {
    dom.apiKeyInput.type = dom.showApiKeyInput.checked ? "text" : "password";
  });
  dom.clearApiKeyButton.addEventListener("click", () => {
    dom.apiKeyInput.value = "";
    store(sessionStorage, SESSION_API_KEY, null);
  });
  for (const button of document.querySelectorAll("[data-close-dialog]")) {
    button.addEventListener("click", () => document.getElementById(button.dataset.closeDialog)?.close());
  }
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && dom.chunkDrawer.classList.contains("open")) closeDrawer();
  });
}

async function initialize() {
  bindEvents();
  const preferredTheme = stored(localStorage, STORAGE.theme,
    window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  setTheme(preferredTheme);
  updateOnlineState();
  updateQuestionCount();
  resizeQuestion();
  switchView(stored(localStorage, STORAGE.view, "chat"), { persist: false });
  await Promise.allSettled([checkRuntime(), loadSpaces()]);
  window.setInterval(() => void poll(), 2500);
}

void initialize();

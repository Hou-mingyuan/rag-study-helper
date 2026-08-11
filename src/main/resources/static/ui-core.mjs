const TERMINAL_JOB_STATES = new Set(["COMPLETED", "FAILED", "CANCELLED", "DEAD"]);

export function consumeSse(buffer, chunk) {
  let source = `${buffer ?? ""}${chunk ?? ""}`;
  const events = [];
  let boundary = findBoundary(source);

  while (boundary) {
    const frame = source.slice(0, boundary.index);
    source = source.slice(boundary.index + boundary.length);
    const parsed = parseSseFrame(frame);
    if (parsed) {
      events.push(parsed);
    }
    boundary = findBoundary(source);
  }

  return { events, rest: source };
}

export function parseSseFrame(frame) {
  if (typeof frame !== "string" || frame.length === 0) {
    return null;
  }

  let event = "message";
  let id = "";
  const data = [];

  for (const line of frame.split(/\r?\n/)) {
    if (line === "" || line.startsWith(":")) {
      continue;
    }
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    if (field === "event") {
      event = value || "message";
    } else if (field === "data") {
      data.push(value);
    } else if (field === "id") {
      id = value;
    }
  }

  if (data.length === 0) {
    return null;
  }
  return { event, id, data: data.join("\n") };
}

export function parseEventJson(data) {
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
}

export function progressPercent(current, total, status = "") {
  const normalized = String(status).toUpperCase();
  if (normalized === "COMPLETED") {
    return 100;
  }
  const numerator = Number(current);
  const denominator = Number(total);
  if (!Number.isFinite(numerator) || !Number.isFinite(denominator) || denominator <= 0) {
    return TERMINAL_JOB_STATES.has(normalized) ? 100 : 0;
  }
  return Math.max(0, Math.min(100, Math.round((numerator / denominator) * 100)));
}

export function formatScore(value) {
  const score = Number(value);
  if (!Number.isFinite(score)) {
    return "—";
  }
  return `${Math.round(Math.max(0, Math.min(1, score)) * 100)}%`;
}

export function formatBytes(value) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "未知大小";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB", "TB"];
  let size = bytes / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size >= 10 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`;
}

export function statusLabel(status) {
  const labels = {
    ACTIVE: "可用",
    READY: "就绪",
    QUEUED: "排队中",
    PROCESSING: "处理中",
    RUNNING: "进行中",
    COMPLETED: "已完成",
    SUCCESS: "成功",
    FAILED: "失败",
    CANCELLED: "已取消",
    DEAD: "需人工处理",
    PENDING: "等待中",
    DELETING: "删除中"
  };
  const normalized = String(status || "PENDING").toUpperCase();
  return labels[normalized] || normalized;
}

export function isActiveJob(status) {
  return ["QUEUED", "PROCESSING", "RUNNING", "PENDING"].includes(String(status).toUpperCase());
}

function findBoundary(source) {
  const match = /\r?\n\r?\n/.exec(source);
  return match ? { index: match.index, length: match[0].length } : null;
}

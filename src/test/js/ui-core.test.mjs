import test from "node:test";
import assert from "node:assert/strict";

import {
  consumeSse,
  formatBytes,
  formatScore,
  isActiveJob,
  parseEventJson,
  progressPercent,
  statusLabel
} from "../../main/resources/static/ui-core.mjs";

test("consumeSse keeps a fragmented event until its boundary arrives", () => {
  const first = consumeSse("", "event: token\r\ndata: {\"token\":\"你");
  assert.deepEqual(first.events, []);

  const second = consumeSse(first.rest, "好\"}\r\n\r\n");
  assert.equal(second.rest, "");
  assert.deepEqual(second.events, [
    { event: "token", id: "", data: "{\"token\":\"你好\"}" }
  ]);
});

test("consumeSse parses multiple events, comments and multiline data", () => {
  const stream = [
    ": heartbeat\n",
    "event: retrieval\n",
    "id: evt-1\n",
    "data: {\"a\":1,\n",
    "data: \"b\":2}\n\n",
    "event: done\n",
    "data: {\"requestId\":\"r1\"}\n\n"
  ].join("");
  const result = consumeSse("", stream);
  assert.equal(result.rest, "");
  assert.deepEqual(result.events, [
    { event: "retrieval", id: "evt-1", data: "{\"a\":1,\n\"b\":2}" },
    { event: "done", id: "", data: "{\"requestId\":\"r1\"}" }
  ]);
});

test("consumeSse ignores frames without data", () => {
  const result = consumeSse("", ": keepalive\n\nretry: 2000\n\n");
  assert.deepEqual(result.events, []);
  assert.equal(result.rest, "");
});

test("progressPercent handles boundaries and terminal states", () => {
  assert.equal(progressPercent(2, 4, "PROCESSING"), 50);
  assert.equal(progressPercent(99, 4, "PROCESSING"), 100);
  assert.equal(progressPercent(-1, 4, "PROCESSING"), 0);
  assert.equal(progressPercent(0, 0, "COMPLETED"), 100);
  assert.equal(progressPercent(undefined, undefined, "QUEUED"), 0);
});

test("formatting helpers reject invalid values and clamp scores", () => {
  assert.equal(formatScore(0.826), "83%");
  assert.equal(formatScore(2), "100%");
  assert.equal(formatScore("bad"), "—");
  assert.equal(formatBytes(1536), "1.5 KB");
  assert.equal(formatBytes(-1), "未知大小");
});

test("status and event helpers expose deterministic semantics", () => {
  assert.equal(statusLabel("completed"), "已完成");
  assert.equal(statusLabel("CUSTOM"), "CUSTOM");
  assert.equal(isActiveJob("PROCESSING"), true);
  assert.equal(isActiveJob("FAILED"), false);
  assert.deepEqual(parseEventJson("{\"ok\":true}"), { ok: true });
  assert.equal(parseEventJson("{"), null);
});

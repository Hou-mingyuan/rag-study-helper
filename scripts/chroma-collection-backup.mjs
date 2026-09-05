#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

function usage() {
  console.error("Usage:");
  console.error("  node scripts/chroma-collection-backup.mjs backup <collection> <output.json> [--base-url URL]");
  console.error("  node scripts/chroma-collection-backup.mjs restore <input.json> <target-collection> [--base-url URL]");
}

function parseArgs(argv) {
  const [command, first, second, ...options] = argv;
  if (!command || !first || !second || !["backup", "restore"].includes(command)) {
    usage();
    process.exit(2);
  }
  let baseUrl = process.env.CHROMA_URL || "http://127.0.0.1:18000";
  if (options.length > 0) {
    if (options.length !== 2 || options[0] !== "--base-url" || !options[1]) {
      usage();
      process.exit(2);
    }
    baseUrl = options[1];
  }
  return { command, first, second, baseUrl: baseUrl.replace(/\/$/, "") };
}

async function request(baseUrl, path, init = {}) {
  const headers = { ...(init.headers || {}) };
  if (init.body) {
    headers["content-type"] = "application/json";
  }
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) {
    throw new Error(`${init.method || "GET"} ${path} returned HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  return body;
}

function rowsFromGet(data) {
  const ids = data?.ids || [];
  const embeddings = data?.embeddings || [];
  const metadatas = data?.metadatas || [];
  const documents = data?.documents || [];
  if (embeddings.length !== ids.length || metadatas.length !== ids.length || documents.length !== ids.length) {
    throw new Error("Chroma export returned arrays with inconsistent lengths");
  }
  return ids.map((id, index) => ({
    id,
    embedding: embeddings[index],
    metadata: metadatas[index],
    document: documents[index]
  })).sort((left, right) => left.id.localeCompare(right.id));
}

function digest(rows) {
  return createHash("sha256").update(JSON.stringify(rows)).digest("hex");
}

async function getCollection(baseUrl, name) {
  return request(baseUrl, `/api/v1/collections/${encodeURIComponent(name)}`);
}

async function readRows(baseUrl, collectionId) {
  const data = await request(baseUrl, `/api/v1/collections/${collectionId}/get`, {
    method: "POST",
    body: JSON.stringify({ include: ["embeddings", "metadatas", "documents"] })
  });
  return rowsFromGet(data);
}

async function backup(baseUrl, collectionName, outputPath) {
  const collection = await getCollection(baseUrl, collectionName);
  const rows = await readRows(baseUrl, collection.id);
  const payload = {
    format: "ragsh-chroma-collection-v1",
    source: {
      name: collection.name,
      id: collection.id,
      metadata: collection.metadata || {}
    },
    count: rows.length,
    dataSha256: digest(rows),
    rows
  };
  const absolute = resolve(outputPath);
  await mkdir(dirname(absolute), { recursive: true });
  await writeFile(absolute, `${JSON.stringify(payload)}\n`, "utf8");
  return { operation: "backup", collection: collectionName, output: absolute,
    count: payload.count, dataSha256: payload.dataSha256 };
}

async function restore(baseUrl, inputPath, targetName) {
  const absolute = resolve(inputPath);
  const payload = JSON.parse(await readFile(absolute, "utf8"));
  if (payload.format !== "ragsh-chroma-collection-v1" || !Array.isArray(payload.rows)) {
    throw new Error("Unsupported or invalid Chroma collection backup");
  }
  if (payload.count !== payload.rows.length || payload.dataSha256 !== digest(payload.rows)) {
    throw new Error("Chroma collection backup checksum or count does not match its contents");
  }
  const collections = await request(baseUrl, "/api/v1/collections");
  if (collections.some((collection) => collection.name === targetName)) {
    throw new Error(`Target Chroma collection already exists: ${targetName}`);
  }

  let target = null;
  try {
    target = await request(baseUrl, "/api/v1/collections", {
      method: "POST",
      body: JSON.stringify({ name: targetName, metadata: payload.source.metadata, get_or_create: false })
    });
    await request(baseUrl, `/api/v1/collections/${target.id}/add`, {
      method: "POST",
      body: JSON.stringify({
        ids: payload.rows.map((row) => row.id),
        embeddings: payload.rows.map((row) => row.embedding),
        metadatas: payload.rows.map((row) => row.metadata),
        documents: payload.rows.map((row) => row.document)
      })
    });
    const restoredRows = await readRows(baseUrl, target.id);
    const restoredDigest = digest(restoredRows);
    if (restoredRows.length !== payload.count || restoredDigest !== payload.dataSha256) {
      throw new Error(`Restored Chroma collection verification failed: count=${restoredRows.length}, sha256=${restoredDigest}`);
    }
    return { operation: "restore", collection: targetName, input: absolute,
      count: restoredRows.length, dataSha256: restoredDigest };
  } catch (error) {
    if (target) {
      try {
        await request(baseUrl, `/api/v1/collections/${encodeURIComponent(targetName)}`, { method: "DELETE" });
      } catch {
        // Preserve the original failure. Cleanup status is visible in Chroma collection listing.
      }
    }
    throw error;
  }
}

const args = parseArgs(process.argv.slice(2));
try {
  const result = args.command === "backup"
    ? await backup(args.baseUrl, args.first, args.second)
    : await restore(args.baseUrl, args.first, args.second);
  console.log(JSON.stringify({ status: "PASS", ...result }, null, 2));
} catch (error) {
  console.error(`CHROMA COLLECTION ${args.command.toUpperCase()} FAILED: ${error.message}`);
  process.exit(1);
}

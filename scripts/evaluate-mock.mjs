#!/usr/bin/env node

import { readFile, readdir, writeFile, mkdir } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import process from 'node:process';

const NO_ANSWER = '根据当前知识空间的资料，没有找到可支持该问题的信息。';
const root = resolve(import.meta.dirname, '..');
const fixtureRoot = resolve(root, 'src/test/resources/evaluation');

const args = parseArgs(process.argv.slice(2));
const baseUrl = (args['base-url'] || 'http://127.0.0.1:19050').replace(/\/$/, '');
const apiKey = args['api-key'] || '';
const headers = apiKey ? { 'X-API-Key': apiKey } : {};
const dataset = JSON.parse(await readFile(resolve(fixtureRoot, 'questions.json'), 'utf8'));
const runId = `${Date.now()}`;

const readiness = await jsonRequest('/api/readiness');
if (readiness.obj?.status !== 'UP' || readiness.obj?.components?.model !== 'MOCK') {
  throw new Error('Evaluation requires a ready application running in Mock mode');
}

const space = (await jsonRequest('/api/spaces', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    name: `Acceptance Eval ${runId}`,
    description: `Fixed evaluation dataset ${dataset.version}`
  })
})).obj;

const documentDir = resolve(fixtureRoot, 'documents');
const files = (await readdir(documentDir)).filter(name => name.endsWith('.md')).sort();
for (const fileName of files) {
  const form = new FormData();
  const content = await readFile(resolve(documentDir, fileName));
  form.append('file', new Blob([content], { type: 'text/markdown' }), fileName);
  const queued = await jsonRequest(`/api/spaces/${space.id}/documents/upload`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `eval-${dataset.version}-${fileName}` },
    body: form
  });
  await waitForJob(space.id, queued.obj.id);
}

const documents = (await jsonRequest(`/api/spaces/${space.id}/documents`)).obj;
const documentById = new Map(documents.map(document => [String(document.id), document.documentName]));
const results = [];
for (const [index, entry] of dataset.questions.entries()) {
  const sessionId = entry.sessionGroup
    ? `eval-${runId}-${entry.sessionGroup}`
    : `eval-${runId}-${String(index + 1).padStart(2, '0')}`;
  const events = await chat(space.id, sessionId, entry.question);
  const retrieval = events.filter(event => event.event === 'retrieval')
    .flatMap(event => event.data.snippets || []);
  const answer = events.filter(event => event.event === 'token')
    .map(event => event.data.token || '').join('');
  const terminals = events.filter(event => ['done', 'error', 'cancelled'].includes(event.event));
  if (terminals.length !== 1 || terminals[0].event !== 'done') {
    throw new Error(`${entry.id} did not finish with exactly one done event`);
  }

  const retrievedNames = retrieval.map(item => item.documentName);
  const expectedRanks = entry.expectedDocuments
    .map(name => retrievedNames.indexOf(name) + 1)
    .filter(rank => rank > 0);
  const bestRank = expectedRanks.length ? Math.min(...expectedRanks) : 0;
  const citations = [...answer.matchAll(/\[(\d+):(\d+)]/g)]
    .map(match => ({ documentId: match[1], chunkId: match[2] }));
  const retrievalPairs = new Set(retrieval.map(item => `${item.documentId}:${item.chunkId}`));
  const groundedCitations = citations.filter(item =>
    retrievalPairs.has(`${item.documentId}:${item.chunkId}`)).length;
  const expectedCitations = citations.filter(item =>
    entry.expectedDocuments.includes(documentById.get(item.documentId))).length;
  const matchedTerms = entry.expectedTerms.filter(term =>
    answer.toLowerCase().includes(String(term).toLowerCase()));

  results.push({
    id: entry.id,
    question: entry.question,
    noAnswer: Boolean(entry.noAnswer),
    expectedDocuments: entry.expectedDocuments,
    retrievedDocuments: retrievedNames,
    bestRank,
    allExpectedRetrieved: entry.expectedDocuments.every(name => retrievedNames.includes(name)),
    citations,
    groundedCitations,
    expectedCitations,
    expectedTerms: entry.expectedTerms,
    matchedTerms,
    answer
  });
}

const answerable = results.filter(result => !result.noAnswer);
const noAnswer = results.filter(result => result.noAnswer);
const crossDocument = results.filter(result =>
  dataset.questions.find(item => item.id === result.id)?.crossDocument);
const totalCitations = answerable.reduce((sum, result) => sum + result.citations.length, 0);
const groundedCitations = answerable.reduce((sum, result) => sum + result.groundedCitations, 0);
const expectedCitations = answerable.reduce((sum, result) => sum + result.expectedCitations, 0);
const totalTerms = answerable.reduce((sum, result) => sum + result.expectedTerms.length, 0);
const matchedTerms = answerable.reduce((sum, result) => sum + result.matchedTerms.length, 0);

const metrics = {
  questionCount: results.length,
  answerableCount: answerable.length,
  noAnswerCount: noAnswer.length,
  hitAt5: ratio(answerable.filter(result => result.bestRank > 0 && result.bestRank <= 5).length,
    answerable.length),
  mrr: average(answerable.map(result => result.bestRank ? 1 / result.bestRank : 0)),
  citationGroundedPrecision: ratio(groundedCitations, totalCitations),
  citationExpectedPrecision: ratio(expectedCitations, totalCitations),
  citationCoverage: ratio(answerable.filter(result => result.citations.length > 0).length,
    answerable.length),
  expectedTermRecall: ratio(matchedTerms, totalTerms),
  noAnswerAccuracy: ratio(noAnswer.filter(result => result.answer.trim() === NO_ANSWER).length,
    noAnswer.length),
  crossDocumentRecall: ratio(crossDocument.filter(result => result.allExpectedRetrieved).length,
    crossDocument.length)
};

const thresholds = {
  questionCount: 20,
  hitAt5: 0.90,
  mrr: 0.70,
  citationGroundedPrecision: 1.0,
  citationExpectedPrecision: 0.85,
  citationCoverage: 0.90,
  expectedTermRecall: 0.80,
  noAnswerAccuracy: 1.0,
  crossDocumentRecall: 0.50
};
const failures = Object.entries(thresholds)
  .filter(([name, threshold]) => metrics[name] < threshold)
  .map(([name, threshold]) => `${name}=${metrics[name]} < ${threshold}`);
const report = {
  datasetVersion: dataset.version,
  generatedAt: new Date().toISOString(),
  baseUrl,
  spaceId: space.id,
  metrics,
  thresholds,
  passed: failures.length === 0,
  failures,
  results
};

const serialized = `${JSON.stringify(report, null, 2)}\n`;
if (args.out) {
  const outPath = resolve(root, args.out);
  await mkdir(dirname(outPath), { recursive: true });
  await writeFile(outPath, serialized, 'utf8');
}
process.stdout.write(serialized);
if (failures.length) {
  process.exitCode = 1;
}

async function waitForJob(spaceId, jobId) {
  const deadline = Date.now() + 45_000;
  while (Date.now() < deadline) {
    const job = (await jsonRequest(`/api/spaces/${spaceId}/jobs/${jobId}`)).obj;
    if (job.status === 'COMPLETED') return job;
    if (job.status === 'FAILED' || job.status === 'CANCELLED') {
      throw new Error(`Ingestion job ${jobId} ended as ${job.status}: ${job.errorMessage || ''}`);
    }
    await new Promise(resolvePromise => setTimeout(resolvePromise, 100));
  }
  throw new Error(`Timed out waiting for ingestion job ${jobId}`);
}

async function chat(spaceId, sessionId, question) {
  const response = await fetch(`${baseUrl}/api/spaces/${spaceId}/chat`, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, question })
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Chat HTTP ${response.status}: ${body.slice(0, 300)}`);
  }
  return body.split(/\r?\n\r?\n/).filter(Boolean).map(block => {
    const lines = block.split(/\r?\n/);
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim();
    const dataText = lines.filter(line => line.startsWith('data:'))
      .map(line => line.slice(5)).join('\n');
    return { event, data: dataText ? JSON.parse(dataText) : {} };
  });
}

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) }
  });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`${path} returned non-JSON HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  if (!response.ok || body.resCode !== '200') {
    throw new Error(`${path} failed HTTP ${response.status}: ${body.msg || text}`);
  }
  return body;
}

function parseArgs(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index++) {
    const value = values[index];
    if (!value.startsWith('--')) throw new Error(`Unknown argument: ${value}`);
    const key = value.slice(2);
    const next = values[index + 1];
    if (!next || next.startsWith('--')) throw new Error(`Missing value for --${key}`);
    parsed[key] = next;
    index++;
  }
  return parsed;
}

function ratio(numerator, denominator) {
  return denominator ? Number((numerator / denominator).toFixed(4)) : 1;
}

function average(values) {
  return values.length
    ? Number((values.reduce((sum, value) => sum + value, 0) / values.length).toFixed(4))
    : 1;
}

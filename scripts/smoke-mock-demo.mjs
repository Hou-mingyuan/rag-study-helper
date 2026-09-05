#!/usr/bin/env node
/** Strict zero-key Docker smoke: scan -> ingest -> retrieve -> SSE -> citation -> session. */

const base = (process.argv[2] || process.env.RAG_SMOKE_BASE_URL || 'http://127.0.0.1:19050')
  .replace(/\/+$/, '')
const apiKey = process.env.RAG_API_KEY || ''
const timeoutMs = Number.parseInt(process.env.RAG_SMOKE_TIMEOUT_MS || '15000', 10)
const attempts = Number.parseInt(process.env.RAG_SMOKE_ATTEMPTS || '40', 10)
const retryDelayMs = Number.parseInt(process.env.RAG_SMOKE_RETRY_DELAY_MS || '2500', 10)
const commonHeaders = apiKey ? { 'X-API-Key': apiKey } : {}

let spaceId
let sessionId
try {
  await waitForReadiness()
  const health = await jsonRequest('/api/health')
  require(health.obj?.ragProvider === 'mock', `expected Mock provider, got ${health.obj?.ragProvider}`)
  require(['in-memory', 'chroma', 'milvus'].includes(health.obj?.vectorStore), 'unknown vector store')
  console.log('ok - health and readiness contracts')

  const root = await fetchWithTimeout(`${base}/`)
  require(root.ok, `root returned HTTP ${root.status}`)
  require(root.headers.get('content-security-policy')?.includes("frame-ancestors 'none'"),
    'root response is missing the security CSP')
  console.log('ok - static UI and security headers')

  const spaces = (await jsonRequest('/api/spaces')).obj || []
  const defaultSpace = spaces.find(space => Number(space.id) === 1) || spaces[0]
  require(defaultSpace, 'default knowledge space is missing')
  spaceId = defaultSpace.id

  let documents = (await jsonRequest(`/api/spaces/${spaceId}/documents`)).obj || []
  if (documents.length === 0) {
    const scanJobs = (await jsonRequest(`/api/spaces/${spaceId}/documents/scan`, { method: 'POST' })).obj || []
    documents = await waitForDocuments(spaceId, scanJobs.map(job => job.id))
  }
  require(documents.length > 0, 'directory scan produced no documents')
  console.log(`ok - directory scan and ingestion (${documents.length} documents)`)

  const session = (await jsonRequest(`/api/spaces/${spaceId}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'Docker smoke' })
  })).obj
  sessionId = session.id

  const events = await streamChat(`/api/spaces/${spaceId}/chat`, {
    sessionId,
    question: 'RAG 的核心流程是什么？'
  })
  const eventNames = events.map(event => event.event)
  for (const required of ['status', 'retrieval', 'token', 'done']) {
    require(eventNames.includes(required), `SSE response is missing ${required}: ${eventNames}`)
  }
  require(!eventNames.some(name => name === 'error' || name === 'cancelled'),
    `SSE ended unexpectedly: ${eventNames}`)
  const snippets = events.filter(event => event.event === 'retrieval')
    .flatMap(event => event.data.snippets || [])
  require(snippets.length > 0, 'retrieval returned no snippets')
  require(snippets.every(item => item.documentId && item.chunkId),
    'retrieval snippets do not contain exact document/chunk ids')
  const answer = events.filter(event => event.event === 'token')
    .map(event => event.data.token || '').join('')
  require(/\[\d+:\d+]/.test(answer), `answer has no grounded citation: ${answer.slice(0, 240)}`)

  const detail = (await jsonRequest(`/api/spaces/${spaceId}/sessions/${sessionId}`)).obj
  require(detail.messages?.length >= 2, 'Redis conversation history was not persisted')
  require(detail.messages.at(-2)?.role === 'user' && detail.messages.at(-1)?.role === 'assistant',
    'conversation turn order is invalid')
  console.log(`ok - SSE retrieval, ${snippets.length} exact citations and Redis session history`)
  console.log(`Mock Docker smoke passed: ${base}`)
} catch (error) {
  console.error(`Mock Docker smoke failed: ${error.message}`)
  process.exitCode = 1
} finally {
  if (spaceId && sessionId) {
    await jsonRequest(`/api/spaces/${spaceId}/sessions/${sessionId}`, { method: 'DELETE' })
      .catch(error => console.error(`Smoke cleanup failed: ${error.message}`))
  }
}

async function waitForReadiness() {
  let lastError
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const body = await jsonRequest('/api/readiness')
      if (body.obj?.status === 'UP') return
      lastError = new Error(`readiness=${body.obj?.status}`)
    } catch (error) {
      lastError = error
    }
    if (attempt < attempts) await sleep(retryDelayMs)
  }
  throw new Error(`readiness did not become UP: ${lastError?.message || 'unknown error'}`)
}

async function waitForDocuments(id, expectedJobIds) {
  const deadline = Date.now() + 90_000
  let lastJobs = []
  while (Date.now() < deadline) {
    lastJobs = (await jsonRequest(`/api/spaces/${id}/jobs`)).obj || []
    const expected = lastJobs.filter(job => expectedJobIds.includes(job.id))
    const failed = expected.find(job => job.status === 'FAILED')
    if (failed) throw new Error(`ingestion job ${failed.id} failed: ${failed.errorMessage || 'unknown'}`)
    if (expected.length === expectedJobIds.length
        && expected.every(job => job.status === 'COMPLETED')) {
      const documents = (await jsonRequest(`/api/spaces/${id}/documents`)).obj || []
      if (documents.length > 0) return documents
    }
    await sleep(250)
  }
  throw new Error(`timed out waiting for documents; jobs=${JSON.stringify(lastJobs).slice(0, 500)}`)
}

async function streamChat(path, payload) {
  const response = await fetchWithTimeout(`${base}${path}`, {
    method: 'POST',
    headers: { ...commonHeaders, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(payload)
  }, 45_000)
  const raw = await response.text()
  require(response.ok, `chat returned HTTP ${response.status}: ${raw.slice(0, 240)}`)
  return raw.split(/\r?\n\r?\n/).filter(Boolean).map(block => {
    const lines = block.split(/\r?\n/)
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
    const dataText = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5)).join('\n')
    return { event, data: dataText ? JSON.parse(dataText) : {} }
  })
}

async function jsonRequest(path, options = {}) {
  const response = await fetchWithTimeout(`${base}${path}`, {
    ...options,
    headers: { ...commonHeaders, ...(options.headers || {}) }
  })
  const raw = await response.text()
  let body
  try {
    body = JSON.parse(raw)
  } catch {
    throw new Error(`${path} returned non-JSON HTTP ${response.status}: ${raw.slice(0, 240)}`)
  }
  require(response.ok && body.resCode === '200',
    `${path} failed HTTP ${response.status}: ${body.msg || raw.slice(0, 240)}`)
  return body
}

async function fetchWithTimeout(url, options = {}, timeout = timeoutMs) {
  return fetch(url, { ...options, signal: AbortSignal.timeout(timeout) })
}

function require(condition, message) {
  if (!condition) throw new Error(message)
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

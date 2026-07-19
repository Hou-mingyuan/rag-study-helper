#!/usr/bin/env node
/**
 * Mock-mode portfolio smoke: health + seeded docs + SSE chat (no API keys).
 */
const rawBase = process.argv[2] || process.env.RAG_SMOKE_BASE_URL || 'http://localhost:8080'
const timeoutMs = Number.parseInt(process.env.RAG_SMOKE_TIMEOUT_MS || '8000', 10)
const maxAttempts = Number.parseInt(process.env.RAG_SMOKE_ATTEMPTS || '15', 10)
const retryDelayMs = Number.parseInt(process.env.RAG_SMOKE_RETRY_DELAY_MS || '3000', 10)

const base = rawBase.replace(/\/+$/, '')

try {
  await runCheck('health mock provider', async () => {
    const body = await getJson(`${base}/api/health`)
    assertResOk(body)
    const provider = body.obj?.ragProvider
    if (provider === 'openai') {
      throw new Error('ragProvider=openai — set APP_RAG_PROVIDER=mock for zero-key demo')
    }
    if (provider && provider !== 'mock') {
      throw new Error(`unexpected ragProvider: ${provider}`)
    }
  })

  await runCheck('seeded documents', async () => {
    const body = await getJson(`${base}/api/documents`)
    assertResOk(body)
    const docs = body.obj
    if (!Array.isArray(docs) || docs.length === 0) {
      const scan = await postJson(`${base}/api/documents/scan`, {})
      assertResOk(scan)
      if (!Array.isArray(scan.obj) || scan.obj.length === 0) {
        throw new Error('no documents after scan — ensure data/docs/*.md is mounted or baked into image')
      }
    }
  })

  await runCheck('mock SSE chat with retrieval', async () => {
    const text = await streamChat(`${base}/api/chat`, {
      sessionId: 'mock-smoke',
      question: 'RAG 中的向量检索是怎么工作的？',
    })
    if (!text.includes('Mock') && !text.includes('参考文档') && !text.includes('向量检索')) {
      throw new Error(`unexpected chat body: ${text.slice(0, 240)}`)
    }
  })

  console.log(`Mock demo smoke passed: ${base}`)
} catch (error) {
  console.error(`Mock demo smoke failed: ${error.message}`)
  process.exitCode = 1
}

async function runCheck(name, fn) {
  let lastError
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      await fn()
      console.log(`ok - ${name}`)
      return
    } catch (error) {
      lastError = error
      if (attempt === maxAttempts) break
      await sleep(retryDelayMs)
    }
  }
  throw lastError
}

function assertResOk(body) {
  if (body?.resCode !== '200') {
    throw new Error(`resCode ${body?.resCode}: ${body?.msg ?? 'unknown'}`)
  }
}

async function getJson(url) {
  const response = await fetchWithTimeout(url)
  return parseJson(response)
}

async function postJson(url, payload) {
  const response = await fetchWithTimeout(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseJson(response)
}

async function streamChat(url, payload) {
  const response = await fetchWithTimeout(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`chat HTTP ${response.status}: ${text.slice(0, 200)}`)
  }
  const raw = await response.text()
  let tokens = ''
  for (const line of raw.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed === '[DONE]' || trimmed === 'data:[DONE]') continue
    const data = trimmed.startsWith('data:') ? trimmed.slice(5).trim() : trimmed
    if (data === '[DONE]') continue
    try {
      const parsed = JSON.parse(data)
      if (parsed.token) tokens += parsed.token
    } catch {
      /* ignore heartbeats */
    }
  }
  if (!tokens) {
    throw new Error(`empty SSE chat response: ${raw.slice(0, 300)}`)
  }
  return tokens
}

async function fetchWithTimeout(url, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error(`${url} timed out after ${timeoutMs}ms`)
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}

async function parseJson(response) {
  const text = await response.text()
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`expected JSON from ${response.url}, got: ${text.slice(0, 200)}`)
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

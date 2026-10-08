import { beforeEach, expect, test, vi } from "vitest"
import { api, ApiError } from './api'
import { useAuth } from './auth'
import { loginAs, mockFetch } from './test-utils'

beforeEach(() => useAuth.getState().logout())

test('api sends the Bearer token when logged in', async () => {
  loginAs()
  const f = mockFetch(['GET', '/api/x', 200, { ok: 1 }])
  expect(await api('/api/x')).toEqual({ ok: 1 })
  expect((f.mock.calls[0][1]!.headers as Record<string, string>).Authorization).toBe(`Bearer ${useAuth.getState().token}`)
})

test('api sends no Authorization header when anonymous', async () => {
  const f = mockFetch(['GET', '/api/x', 200, {}])
  await api('/api/x')
  expect((f.mock.calls[0][1]!.headers as Record<string, string>).Authorization).toBeUndefined()
})

test('api sends a JSON body with method and extra headers', async () => {
  const f = mockFetch(['POST', '/api/x', 201, { id: 1 }])
  await api('/api/x', { method: 'POST', body: { a: 1 }, headers: { 'Idempotency-Key': 'k' } })
  const init = f.mock.calls[0][1]!
  expect(init.body).toBe('{"a":1}')
  expect(init.headers).toMatchObject({ 'Content-Type': 'application/json', 'Idempotency-Key': 'k' })
})

test('api returns undefined for an empty 204 response', async () => {
  mockFetch(['DELETE', '/api/x', 204])
  expect(await api('/api/x', { method: 'DELETE' })).toBeUndefined()
})

test('api throws ApiError with status, code and message from the error body', async () => {
  mockFetch(['GET', '/api/x', 409, { code: 'MARKET_NOT_OPEN', message: 'Market is not open' }])
  const e = await api('/api/x').catch((x) => x)
  expect(e).toBeInstanceOf(ApiError)
  expect([e.status, e.code, e.message]).toEqual([409, 'MARKET_NOT_OPEN', 'Market is not open'])
})

test('api throws ApiError with a fallback message when the error body is not JSON', async () => {
  vi.stubGlobal('fetch', async () => new Response('boom', { status: 503 }))
  const e = await api('/api/x').catch((x) => x)
  expect([e.status, e.code, e.message]).toEqual([503, 'HTTP_503', 'Request failed (503)'])
})

test('a 401 response logs the user out', async () => {
  loginAs()
  mockFetch(['GET', '/api/x', 401, { code: 'UNAUTHORIZED', message: 'no' }])
  await expect(api('/api/x')).rejects.toThrow('no')
  expect(useAuth.getState().token).toBeNull()
})

import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { vi } from 'vitest'
import { useAuth } from './auth'

export function jwt(payload: object) {
  return `h.${btoa(JSON.stringify(payload)).replace(/=+$/, '')}.s`
}

export function loginAs(roles = ['USER'], sub = 'u1') {
  useAuth.getState().login(jwt({ sub, roles, exp: Date.now() / 1000 + 3600 }))
}

export function renderWithQuery(ui: ReactNode, qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return { qc, ...render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>) }
}

type Route = readonly [method: string, url: string | RegExp, status: number, body?: unknown]

/** Stubs fetch; routes match "METHOD url" (url string = prefix match, path + query). */
export function mockFetch(...routes: readonly Route[]) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const r = routes.find(([m, u]) => m === method && (typeof u === 'string' ? url === u : u.test(url)))
    if (!r) return new Response(JSON.stringify({ code: 'NOT_MOCKED', message: `${method} ${url}` }), { status: 500 })
    return new Response(r[3] === undefined ? null : JSON.stringify(r[3]), { status: r[2] })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

export const market = {
  marketId: 'm1', title: 'Will it rain?', description: 'Rain in Kyiv', status: 'OPEN', winningSide: null as string | null,
  freezeAt: '2026-10-10T10:00:00Z', endAt: '2026-10-11T10:00:00Z', minBet: 1, maxBet: 100,
  truePool: 60, falsePool: 40, totalPool: 100, betCount: 3, uniqueBettors: 2, oddsTrue: 1.6, oddsFalse: 2.4,
}

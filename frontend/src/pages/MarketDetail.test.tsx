import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test, vi } from 'vitest'
import { useAuth } from '../auth'
import { loginAs, market, mockFetch, renderWithQuery } from '../test-utils'
import MarketDetail from './MarketDetail'

const bet = (o: object) => ({ betId: 'b1', marketId: 'm1', userId: 'u1', side: 'TRUE', amount: 5, status: 'ACCEPTED', placedAt: '2026-10-01T00:00:00Z', ...o })
const bets = (...items: object[]) => ({ items, page: 0, size: 100, total: items.length })
const wallet = { userId: 'u1', balance: 100, reserved: 0, available: 100 }
const base = (m = market, myBets = bets()) => [
  ['GET', '/api/markets/m1', 200, m],
  ['GET', '/api/me/bets?size=100', 200, myBets],
  ['GET', '/api/wallets/me', 200, wallet],
] as const

/** Fetch where one route stays pending until release() is called. */
function deferredFetch(method: string, url: string, status: number, body: unknown, m = market, myBets = bets()) {
  let release!: () => void
  const gate = new Promise<void>((r) => { release = r })
  const routes = [...base(m, myBets)]
  const fn = vi.fn(async (u: string, init?: RequestInit) => {
    const meth = init?.method ?? 'GET'
    if (meth === method && u === url) { await gate; return new Response(JSON.stringify(body), { status }) }
    const r = routes.find(([mm, uu]) => mm === meth && uu === u)!
    return new Response(JSON.stringify(r[3]), { status: r[2] })
  })
  vi.stubGlobal('fetch', fn)
  return { fn, release }
}

beforeEach(() => useAuth.getState().logout())

test('market detail shows info, pools and odds', async () => {
  mockFetch(...base())
  renderWithQuery(<MarketDetail id="m1" />)
  expect(await screen.findByRole('heading', { name: 'Will it rain?' })).toBeInTheDocument()
  expect(screen.getByText('Rain in Kyiv')).toBeInTheDocument()
  expect(screen.getByText(/TRUE pool: 60/)).toBeInTheDocument()
  expect(screen.getByText(/FALSE pool: 40/)).toBeInTheDocument()
  expect(screen.getByText(/Odds: 1.60 \/ 2.40/)).toBeInTheDocument()
})

test('market detail hides the bet form from anonymous users', async () => {
  mockFetch(...base())
  renderWithQuery(<MarketDetail id="m1" />)
  await screen.findByRole('heading', { name: 'Will it rain?' })
  expect(screen.queryByRole('button', { name: 'Place bet' })).toBeNull()
})

test('market detail hides the bet form when the market is not OPEN', async () => {
  loginAs()
  mockFetch(...base({ ...market, status: 'FROZEN' }))
  renderWithQuery(<MarketDetail id="m1" />)
  await screen.findByRole('heading', { name: 'Will it rain?' })
  expect(screen.queryByRole('button', { name: 'Place bet' })).toBeNull()
})

test('bet form amount uses min and max from the market', async () => {
  loginAs()
  mockFetch(...base())
  renderWithQuery(<MarketDetail id="m1" />)
  const amount = await screen.findByLabelText('Amount')
  expect(amount).toHaveAttribute('min', '1')
  expect(amount).toHaveAttribute('max', '100')
})

test('placing a bet adds a PENDING bet and lowers available at once, then uses the server betId', async () => {
  loginAs()
  const { fn, release } = deferredFetch('POST', '/api/bets', 202, { betId: 'b9', status: 'ACCEPTED' })
  const { qc } = renderWithQuery(<MarketDetail id="m1" />)
  await waitFor(() => expect(qc.getQueryData(['wallet'])).toEqual(wallet))
  await userEvent.selectOptions(await screen.findByLabelText('Side'), 'FALSE')
  await userEvent.clear(screen.getByLabelText('Amount')); await userEvent.type(screen.getByLabelText('Amount'), '7')
  await userEvent.click(screen.getByRole('button', { name: 'Place bet' }))
  expect(await screen.findByText('PENDING')).toBeInTheDocument()
  expect((qc.getQueryData(['wallet']) as typeof wallet).available).toBe(93)
  const post = fn.mock.calls.find(([, i]) => i?.method === 'POST')![1]!
  expect(JSON.parse(post.body as string)).toEqual({ marketId: 'm1', side: 'FALSE', amount: 7 })
  expect((post.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)
  const invalidate = vi.spyOn(qc, 'invalidateQueries').mockResolvedValue() // no refetch: keep the replaced bet in cache
  release()
  await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['myBets'] }))
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['wallet'] })
  expect((qc.getQueryData(['myBets']) as any).items.some((b: any) => b.betId === 'b9' && b.status === 'ACCEPTED')).toBe(true)
})

test('a failed bet rolls back the PENDING bet and available and shows the error', async () => {
  loginAs()
  const { release } = deferredFetch('POST', '/api/bets', 422, { code: 'INSUFFICIENT_FUNDS', message: 'Not enough money' })
  const { qc } = renderWithQuery(<MarketDetail id="m1" />)
  await waitFor(() => expect(qc.getQueryData(['wallet'])).toEqual(wallet))
  await userEvent.clear(await screen.findByLabelText('Amount')); await userEvent.type(screen.getByLabelText('Amount'), '7')
  await userEvent.click(screen.getByRole('button', { name: 'Place bet' }))
  expect(await screen.findByText('PENDING')).toBeInTheDocument()
  release()
  expect(await screen.findByRole('alert')).toHaveTextContent('Not enough money')
  expect(screen.queryByText('PENDING')).toBeNull()
  expect((qc.getQueryData(['wallet']) as typeof wallet).available).toBe(100)
})

test('market detail lists only my bets on this market', async () => {
  loginAs()
  mockFetch(...base(market, bets(bet({ betId: 'b1', amount: 5 }), bet({ betId: 'b2', marketId: 'other', amount: 77 }))))
  renderWithQuery(<MarketDetail id="m1" />)
  const list = await screen.findByRole('list', { name: 'My bets' })
  await within(list).findByText(/5/)
  expect(within(list).queryByText(/77/)).toBeNull()
})

test('cancel shows only for ACCEPTED bets while the market is OPEN', async () => {
  loginAs()
  mockFetch(...base({ ...market, status: 'FROZEN' }, bets(bet({}))))
  renderWithQuery(<MarketDetail id="m1" />)
  await screen.findByText('ACCEPTED')
  expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull()
})

test('cancelling a bet marks it CANCELLED at once and rolls back on error', async () => {
  loginAs()
  const { release } = deferredFetch('DELETE', '/api/bets/b1', 409, { code: 'MARKET_NOT_OPEN', message: 'Too late' }, market, bets(bet({})))
  renderWithQuery(<MarketDetail id="m1" />)
  await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
  expect(await screen.findByText('CANCELLED')).toBeInTheDocument()
  release()
  expect(await screen.findByRole('alert')).toHaveTextContent('Too late')
  expect(screen.getByText('ACCEPTED')).toBeInTheDocument()
})

test('cancelling a bet succeeds and refetches my bets', async () => {
  loginAs()
  const { fn, release } = deferredFetch('DELETE', '/api/bets/b1', 200, { betId: 'b1', status: 'CANCELLED' }, market, bets(bet({})))
  renderWithQuery(<MarketDetail id="m1" />)
  await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
  release()
  await waitFor(() => expect(fn.mock.calls.filter(([u]) => u === '/api/me/bets?size=100').length).toBeGreaterThan(1))
})

test('admin sees Freeze for an OPEN market and it posts freeze', async () => {
  loginAs(['USER', 'ADMIN'])
  const f = mockFetch(...base(), ['POST', '/api/markets/m1/freeze', 200, { ...market, status: 'FROZEN' }])
  renderWithQuery(<MarketDetail id="m1" />)
  await userEvent.click(await screen.findByRole('button', { name: 'Freeze' }))
  await waitFor(() => expect(f).toHaveBeenCalledWith('/api/markets/m1/freeze', expect.objectContaining({ method: 'POST' })))
  expect(screen.queryByRole('button', { name: 'Resolve TRUE' })).toBeNull()
})

test('admin sees Resolve TRUE / Resolve FALSE for an ENDED market and it posts the winning side', async () => {
  loginAs(['USER', 'ADMIN'])
  const f = mockFetch(...base({ ...market, status: 'ENDED' }), ['POST', '/api/markets/m1/resolve', 200, market])
  renderWithQuery(<MarketDetail id="m1" />)
  expect(await screen.findByRole('button', { name: 'Resolve TRUE' })).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Resolve FALSE' }))
  await waitFor(() => expect(f).toHaveBeenCalledWith('/api/markets/m1/resolve', expect.objectContaining({ body: '{"winningSide":"FALSE"}' })))
})

test('admin can cancel a market that is not RESOLVED or CANCELLED', async () => {
  loginAs(['USER', 'ADMIN'])
  const f = mockFetch(...base(), ['DELETE', '/api/markets/m1', 204])
  renderWithQuery(<MarketDetail id="m1" />)
  await userEvent.click(await screen.findByRole('button', { name: 'Cancel market' }))
  await waitFor(() => expect(f).toHaveBeenCalledWith('/api/markets/m1', expect.objectContaining({ method: 'DELETE' })))
})

test('admin sees no market actions for a RESOLVED market', async () => {
  loginAs(['USER', 'ADMIN'])
  mockFetch(...base({ ...market, status: 'RESOLVED', winningSide: 'TRUE' }))
  renderWithQuery(<MarketDetail id="m1" />)
  await screen.findByRole('heading', { name: 'Will it rain?' })
  expect(screen.queryByRole('button', { name: 'Cancel market' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Freeze' })).toBeNull()
  expect(screen.getByText(/Winner: TRUE/)).toBeInTheDocument()
})

test('a failed admin action shows the error message', async () => {
  loginAs(['USER', 'ADMIN'])
  mockFetch(...base(), ['POST', '/api/markets/m1/freeze', 409, { code: 'BAD_STATE', message: 'Cannot freeze' }])
  renderWithQuery(<MarketDetail id="m1" />)
  await userEvent.click(await screen.findByRole('button', { name: 'Freeze' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Cannot freeze')
})

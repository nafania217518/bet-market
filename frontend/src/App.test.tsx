import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import { useAuth } from './auth'
import { useToasts } from './live'
import { loginAs, market, mockFetch, renderWithQuery } from './test-utils'

vi.mock('./live', async (orig) => ({ ...(await orig<typeof import('./live')>()), startLive: vi.fn(() => () => {}) }))

const go = (h: string) => act(() => { location.hash = h; window.dispatchEvent(new HashChangeEvent('hashchange')) })
const routes = () => mockFetch(
  ['GET', /^\/api\/markets\?/, 200, { items: [market], page: 0, size: 50, total: 1 }],
  ['GET', '/api/markets/m1', 200, market],
  ['GET', /^\/api\/me\/bets/, 200, { items: [], page: 0, size: 50, total: 0 }],
  ['GET', '/api/wallets/me', 200, { userId: 'u1', balance: 100, reserved: 20, available: 80 }],
  ['GET', /^\/api\/wallets\/me\/ledger/, 200, { entries: [], page: 0, size: 50 }],
  ['GET', /^\/api\/leaderboard/, 200, { window: 'all', entries: [] }],
)

beforeEach(() => { useAuth.getState().logout(); useToasts.setState({ toasts: [] }); location.hash = '#/' })

test('app routes each hash to its page', async () => {
  routes()
  loginAs()
  renderWithQuery(<App />)
  expect(await screen.findByRole('heading', { name: 'Markets' })).toBeInTheDocument()
  go('#/markets/m1'); expect(await screen.findByRole('heading', { name: 'Will it rain?' })).toBeInTheDocument()
  go('#/bets'); expect(await screen.findByRole('heading', { name: 'My bets' })).toBeInTheDocument()
  go('#/wallet'); expect(await screen.findByRole('heading', { name: 'Wallet' })).toBeInTheDocument()
  go('#/leaderboard'); expect(await screen.findByRole('heading', { name: 'Leaderboard' })).toBeInTheDocument()
  go('#/login'); expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
  go('#/admin'); expect(await screen.findByText('Admins only')).toBeInTheDocument()
})

test('header shows the Admin link only to admins', async () => {
  routes()
  loginAs(['USER'])
  const { unmount } = renderWithQuery(<App />)
  expect(screen.queryByRole('link', { name: 'Admin' })).toBeNull()
  unmount()
  loginAs(['USER', 'ADMIN'])
  renderWithQuery(<App />)
  expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '#/admin')
})

test('header shows the available balance and a logout button when logged in', async () => {
  routes()
  loginAs()
  renderWithQuery(<App />)
  expect(await screen.findByText('Available: 80')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Log out' }))
  expect(useAuth.getState().token).toBeNull()
  expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument()
})

test('app shows toasts and dismisses them on click', async () => {
  routes()
  renderWithQuery(<App />)
  act(() => useToasts.getState().push('You won 20'))
  await userEvent.click(screen.getByRole('button', { name: 'You won 20' }))
  await waitFor(() => expect(screen.queryByText('You won 20')).toBeNull())
})

test('app starts live updates', async () => {
  const { startLive } = await import('./live')
  routes()
  renderWithQuery(<App />)
  expect(startLive).toHaveBeenCalled()
})

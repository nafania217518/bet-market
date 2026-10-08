import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import { useAuth } from '../auth'
import { loginAs, mockFetch, renderWithQuery } from '../test-utils'
import MyBets from './MyBets'

beforeEach(() => useAuth.getState().logout())

test('my bets asks anonymous users to log in', () => {
  renderWithQuery(<MyBets />)
  expect(screen.getByRole('link', { name: /log in/i })).toHaveAttribute('href', '#/login')
})

test('my bets lists my bets with a link to the market', async () => {
  loginAs()
  mockFetch(['GET', /^\/api\/me\/bets/, 200, { items: [{ betId: 'b1', marketId: 'm1', userId: 'u1', side: 'TRUE', amount: 5, status: 'WON', placedAt: '2026-10-01T00:00:00Z' }], page: 0, size: 50, total: 1 }])
  renderWithQuery(<MyBets />)
  const row = (await screen.findByRole('link', { name: 'm1' })).closest('tr')!
  expect(row).toHaveTextContent('TRUE')
  expect(row).toHaveTextContent('5')
  expect(row).toHaveTextContent('WON')
})

test('my bets filters by status', async () => {
  loginAs()
  const f = mockFetch(['GET', /^\/api\/me\/bets/, 200, { items: [], page: 0, size: 50, total: 0 }])
  renderWithQuery(<MyBets />)
  await waitFor(() => expect(f.mock.calls[0][0]).not.toContain('status='))
  await userEvent.selectOptions(screen.getByLabelText('Status'), 'LOST')
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('status=LOST'))
})

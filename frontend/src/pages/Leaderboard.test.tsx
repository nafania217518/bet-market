import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { mockFetch, renderWithQuery } from '../test-utils'
import Leaderboard from './Leaderboard'

test('leaderboard shows rank, displayName and netProfit', async () => {
  mockFetch(['GET', /^\/api\/leaderboard/, 200, { window: 'all', entries: [{ rank: 1, userId: 'u1', displayName: 'Neo', netProfit: 42 }] }])
  renderWithQuery(<Leaderboard />)
  const row = (await screen.findByText('Neo')).closest('tr')!
  expect(row).toHaveTextContent('1')
  expect(row).toHaveTextContent('42')
})

test('leaderboard switches window between all, 7d and 30d', async () => {
  const f = mockFetch(['GET', /^\/api\/leaderboard/, 200, { window: 'all', entries: [] }])
  renderWithQuery(<Leaderboard />)
  await waitFor(() => expect(f.mock.calls[0][0]).toContain('window=all'))
  await userEvent.click(screen.getByRole('button', { name: '7d' }))
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('window=7d'))
  await userEvent.click(screen.getByRole('button', { name: '30d' }))
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('window=30d'))
})

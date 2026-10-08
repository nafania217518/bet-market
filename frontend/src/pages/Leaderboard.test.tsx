import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { mockFetch, renderWithQuery } from '../test-utils'
import Leaderboard from './Leaderboard'

const e = (rank: number, displayName: string, netProfit: number) => ({ rank, userId: `u${rank}`, displayName, netProfit })

test('leaderboard shows the top 3 on a podium and the rest in a table with rank, name and net profit', async () => {
  mockFetch(['GET', /^\/api\/leaderboard/, 200, { window: 'all', entries: [e(1, 'Neo', 42), e(2, 'Ann', 30), e(3, 'Bob', 10), e(4, 'Eve', -5)] }])
  renderWithQuery(<Leaderboard />)
  const podium = await screen.findByRole('list', { name: 'Top 3' })
  expect(within(podium).getByText('Neo').closest('li')).toHaveTextContent('+42')
  expect(within(podium).getByText('Bob')).toBeInTheDocument()
  const row = screen.getByText('Eve').closest('tr')!
  expect(row).toHaveTextContent('4')
  expect(row).toHaveTextContent('-5')
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

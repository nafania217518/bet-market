import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { market, mockFetch, renderWithQuery } from '../test-utils'
import Markets from './Markets'

const page = { items: [market], page: 0, size: 50, total: 1 }

test('markets list shows title, status, total pool, odds and the time label, linking to the market', async () => {
  mockFetch(['GET', /^\/api\/markets\?/, 200, page])
  renderWithQuery(<Markets />)
  const link = await screen.findByRole('link', { name: 'Will it rain?' })
  expect(link).toHaveAttribute('href', '#/markets/m1')
  const row = link.closest('article')!
  expect(row).toHaveTextContent('OPEN')
  expect(row).toHaveTextContent('100')
  expect(row).toHaveTextContent('1.60')
  expect(row).toHaveTextContent('2.40')
  expect(within(row).getByRole('img', { name: 'TRUE 60%, FALSE 40%' })).toBeInTheDocument()
  expect(row).toHaveTextContent(/Freezes in|Freezing now/)
})

test('markets list shows a dash for null odds', async () => {
  mockFetch(['GET', /^\/api\/markets\?/, 200, { ...page, items: [{ ...market, oddsTrue: null, oddsFalse: null }] }])
  renderWithQuery(<Markets />)
  const row = (await screen.findByRole('link', { name: 'Will it rain?' })).closest('article')!
  expect(row).toHaveTextContent('TRUE —')
  expect(row).toHaveTextContent('FALSE —')
})

test('markets list filters by OPEN by default and omits status for "all"', async () => {
  const f = mockFetch(['GET', /^\/api\/markets\?/, 200, page])
  renderWithQuery(<Markets />)
  await waitFor(() => expect(f.mock.calls[0][0]).toContain('status=OPEN'))
  await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'all' }))
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).not.toContain('status='))
  await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'RESOLVED' }))
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('status=RESOLVED'))
})

test('markets list sorts by endAt by default and by pool on request', async () => {
  const f = mockFetch(['GET', /^\/api\/markets\?/, 200, page])
  renderWithQuery(<Markets />)
  await waitFor(() => expect(f.mock.calls[0][0]).toContain('sort=endAt'))
  await userEvent.click(within(screen.getByRole('group', { name: 'Sort' })).getByRole('button', { name: 'pool' }))
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('sort=pool'))
})

test('markets list shows a friendly message when there are no markets', async () => {
  mockFetch(['GET', /^\/api\/markets\?/, 200, { ...page, items: [] }])
  renderWithQuery(<Markets />)
  expect(await screen.findByText('No markets here yet')).toBeInTheDocument()
})

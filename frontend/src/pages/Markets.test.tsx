import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { market, mockFetch, renderWithQuery } from '../test-utils'
import Markets from './Markets'

const page = { items: [market], page: 0, size: 50, total: 1 }

test('markets list shows title, status, total pool, odds and freezeAt, linking to the market', async () => {
  mockFetch(['GET', /^\/api\/markets\?/, 200, page])
  renderWithQuery(<Markets />)
  const link = await screen.findByRole('link', { name: 'Will it rain?' })
  expect(link).toHaveAttribute('href', '#/markets/m1')
  const row = link.closest('tr')!
  expect(row).toHaveTextContent('OPEN')
  expect(row).toHaveTextContent('100')
  expect(row).toHaveTextContent('1.60')
  expect(row).toHaveTextContent('2.40')
  expect(row).toHaveTextContent(new Date(market.freezeAt).toLocaleString())
})

test('markets list shows a dash for null odds', async () => {
  mockFetch(['GET', /^\/api\/markets\?/, 200, { ...page, items: [{ ...market, oddsTrue: null, oddsFalse: null }] }])
  renderWithQuery(<Markets />)
  const row = (await screen.findByRole('link', { name: 'Will it rain?' })).closest('tr')!
  expect(row).toHaveTextContent('— / —')
})

test('markets list filters by OPEN by default and omits status for "all"', async () => {
  const f = mockFetch(['GET', /^\/api\/markets\?/, 200, page])
  renderWithQuery(<Markets />)
  await waitFor(() => expect(f.mock.calls[0][0]).toContain('status=OPEN'))
  await userEvent.selectOptions(screen.getByLabelText('Status'), 'all')
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).not.toContain('status='))
  await userEvent.selectOptions(screen.getByLabelText('Status'), 'RESOLVED')
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('status=RESOLVED'))
})

test('markets list sorts by endAt by default and by pool on request', async () => {
  const f = mockFetch(['GET', /^\/api\/markets\?/, 200, page])
  renderWithQuery(<Markets />)
  await waitFor(() => expect(f.mock.calls[0][0]).toContain('sort=endAt'))
  await userEvent.selectOptions(screen.getByLabelText('Sort'), 'pool')
  await waitFor(() => expect(f.mock.calls.at(-1)![0]).toContain('sort=pool'))
})

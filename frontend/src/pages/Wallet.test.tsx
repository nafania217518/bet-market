import { screen } from '@testing-library/react'
import { beforeEach, expect, test } from 'vitest'
import { useAuth } from '../auth'
import { loginAs, mockFetch, renderWithQuery } from '../test-utils'
import Wallet from './Wallet'

beforeEach(() => useAuth.getState().logout())

test('wallet asks anonymous users to log in', () => {
  renderWithQuery(<Wallet />)
  expect(screen.getByRole('link', { name: /log in/i })).toHaveAttribute('href', '#/login')
})

test('wallet shows balance, reserved, available and the ledger', async () => {
  loginAs()
  mockFetch(
    ['GET', '/api/wallets/me', 200, { userId: 'u1', balance: 100, reserved: 30, available: 70 }],
    ['GET', /^\/api\/wallets\/me\/ledger/, 200, { entries: [{ id: 'e1', betId: 'b1', type: 'RESERVE', amount: -30, balanceAfter: 100, createdAt: '2026-10-01T00:00:00Z' }], page: 0, size: 50 }],
  )
  renderWithQuery(<Wallet />)
  expect(await screen.findByRole('group', { name: 'Balance' })).toHaveTextContent('100')
  expect(screen.getByRole('group', { name: 'Reserved' })).toHaveTextContent('30')
  expect(screen.getByRole('group', { name: 'Available' })).toHaveTextContent('70')
  const row = (await screen.findByText('RESERVE')).closest('tr')!
  expect(row).toHaveTextContent('-30')
  expect(row).toHaveTextContent('b1')
})

test('wallet ledger shows credits with a plus sign', async () => {
  loginAs()
  mockFetch(
    ['GET', '/api/wallets/me', 200, { userId: 'u1', balance: 120, reserved: 0, available: 120 }],
    ['GET', /^\/api\/wallets\/me\/ledger/, 200, { entries: [{ id: 'e2', betId: null, type: 'CREDIT', amount: 20, balanceAfter: 120, createdAt: '2026-10-01T00:00:00Z' }], page: 0, size: 50 }],
  )
  renderWithQuery(<Wallet />)
  expect((await screen.findByText('CREDIT')).closest('tr')).toHaveTextContent('+20')
})

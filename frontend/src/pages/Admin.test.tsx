import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import { useAuth } from '../auth'
import { loginAs, market, mockFetch, renderWithQuery } from '../test-utils'
import Admin from './Admin'

beforeEach(() => { useAuth.getState().logout(); location.hash = '#/admin' })

test('admin page shows "Admins only" to non-admins', () => {
  loginAs(['USER'])
  renderWithQuery(<Admin />)
  expect(screen.getByText('Admins only')).toBeInTheDocument()
})

test('create-market form sends ISO UTC instants and goes to the new market', async () => {
  loginAs(['USER', 'ADMIN'])
  const f = mockFetch(['POST', '/api/markets', 201, { ...market, marketId: 'm9' }])
  renderWithQuery(<Admin />)
  await userEvent.type(screen.getByLabelText('Title'), 'T')
  await userEvent.type(screen.getByLabelText('Description'), 'D')
  await userEvent.type(screen.getByLabelText('Freeze at'), '2026-10-10T12:00')
  await userEvent.type(screen.getByLabelText('End at'), '2026-10-11T12:00')
  await userEvent.clear(screen.getByLabelText('Min bet')); await userEvent.type(screen.getByLabelText('Min bet'), '2')
  await userEvent.clear(screen.getByLabelText('Max bet')); await userEvent.type(screen.getByLabelText('Max bet'), '50')
  await userEvent.click(screen.getByRole('button', { name: 'Create market' }))
  await waitFor(() => expect(location.hash).toBe('#/markets/m9'))
  expect(JSON.parse(f.mock.calls[0][1]!.body as string)).toEqual({
    title: 'T', description: 'D', minBet: 2, maxBet: 50,
    freezeAt: new Date('2026-10-10T12:00').toISOString(), endAt: new Date('2026-10-11T12:00').toISOString(),
  })
})

test('create-market form shows the API error message', async () => {
  loginAs(['USER', 'ADMIN'])
  mockFetch(['POST', '/api/markets', 422, { code: 'VALIDATION', message: 'endAt must be after freezeAt' }])
  renderWithQuery(<Admin />)
  await userEvent.type(screen.getByLabelText('Title'), 'T')
  await userEvent.type(screen.getByLabelText('Freeze at'), '2026-10-10T12:00')
  await userEvent.type(screen.getByLabelText('End at'), '2026-10-09T12:00')
  await userEvent.click(screen.getByRole('button', { name: 'Create market' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('endAt must be after freezeAt')
})

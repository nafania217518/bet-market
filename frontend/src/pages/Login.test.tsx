import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import { useAuth } from '../auth'
import { jwt, mockFetch, renderWithQuery } from '../test-utils'
import Login from './Login'

const token = jwt({ sub: 'u1', roles: ['USER'], exp: Date.now() / 1000 + 3600 })
beforeEach(() => { useAuth.getState().logout(); location.hash = '#/login' })

test('login form logs in with email and password and goes to #/', async () => {
  const f = mockFetch(['POST', '/api/auth/login', 200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }])
  renderWithQuery(<Login />)
  await userEvent.type(screen.getAllByLabelText('Email')[0], 'a@b.c')
  await userEvent.type(screen.getAllByLabelText('Password')[0], 'secret')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
  await waitFor(() => expect(location.hash).toBe('#/'))
  expect(useAuth.getState().userId).toBe('u1')
  expect(JSON.parse(f.mock.calls[0][1]!.body as string)).toEqual({ email: 'a@b.c', password: 'secret' })
})

test('login shows the API error message on failure', async () => {
  mockFetch(['POST', '/api/auth/login', 401, { code: 'BAD_CREDENTIALS', message: 'Wrong email or password' }])
  renderWithQuery(<Login />)
  await userEvent.type(screen.getAllByLabelText('Email')[0], 'a@b.c')
  await userEvent.type(screen.getAllByLabelText('Password')[0], 'x')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password')
  expect(location.hash).toBe('#/login')
})

test('register form registers, then logs in and goes to #/', async () => {
  const f = mockFetch(
    ['POST', '/api/auth/register', 201, { userId: 'u1' }],
    ['POST', '/api/auth/login', 200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }],
  )
  renderWithQuery(<Login />)
  await userEvent.type(screen.getAllByLabelText('Email')[1], 'n@b.c')
  await userEvent.type(screen.getAllByLabelText('Password')[1], 'pw123456')
  await userEvent.type(screen.getByLabelText('Display name'), 'Neo')
  await userEvent.click(screen.getByRole('button', { name: 'Register' }))
  await waitFor(() => expect(location.hash).toBe('#/'))
  expect(JSON.parse(f.mock.calls[0][1]!.body as string)).toEqual({ email: 'n@b.c', password: 'pw123456', displayName: 'Neo' })
  expect(useAuth.getState().token).toBe(token)
})

test('register shows the API error message on failure', async () => {
  mockFetch(['POST', '/api/auth/register', 409, { code: 'EMAIL_TAKEN', message: 'Email already used' }])
  renderWithQuery(<Login />)
  await userEvent.type(screen.getAllByLabelText('Email')[1], 'n@b.c')
  await userEvent.type(screen.getAllByLabelText('Password')[1], 'pw')
  await userEvent.type(screen.getByLabelText('Display name'), 'Neo')
  await userEvent.click(screen.getByRole('button', { name: 'Register' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Email already used')
})

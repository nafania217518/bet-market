import { beforeEach, expect, test } from 'vitest'
import { useAuth, isLoggedIn, isAdmin } from './auth'
import { jwt } from './test-utils'

beforeEach(() => { useAuth.getState().logout(); localStorage.clear() })

test('login decodes the JWT payload into userId, roles and exp', () => {
  const exp = Date.now() / 1000 + 60
  useAuth.getState().login(jwt({ sub: 'u1', roles: ['USER', 'ADMIN'], exp }))
  const s = useAuth.getState()
  expect([s.userId, s.roles, s.exp]).toEqual(['u1', ['USER', 'ADMIN'], exp])
  expect(isLoggedIn(s)).toBe(true)
  expect(isAdmin(s)).toBe(true)
})

test('login persists the token to localStorage', () => {
  useAuth.getState().login(jwt({ sub: 'u1', roles: ['USER'], exp: Date.now() / 1000 + 60 }))
  expect(localStorage.getItem('auth')).toContain('u1')
})

test('an expired token counts as logged out', () => {
  useAuth.getState().login(jwt({ sub: 'u1', roles: ['USER'], exp: Date.now() / 1000 - 1 }))
  expect(isLoggedIn(useAuth.getState())).toBe(false)
})

test('logout clears the token', () => {
  useAuth.getState().login(jwt({ sub: 'u1', roles: ['USER'], exp: Date.now() / 1000 + 60 }))
  useAuth.getState().logout()
  expect(useAuth.getState().token).toBeNull()
  expect(isAdmin(useAuth.getState())).toBe(false)
})

test('login decodes base64url payloads', () => {
  // payload containing chars that encode to '-' / '_' in base64url
  const json = JSON.stringify({ sub: 'ü?>', roles: ['USER'], exp: Date.now() / 1000 + 60 })
  const b64url = btoa(String.fromCharCode(...new TextEncoder().encode(json))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  useAuth.getState().login(`h.${b64url}.s`)
  expect(useAuth.getState().userId).toBe('ü?>')
})

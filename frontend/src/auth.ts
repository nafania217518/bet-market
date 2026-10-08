import { create } from 'zustand'
import { persist } from 'zustand/middleware'

type AuthState = {
  token: string | null; userId: string | null; roles: string[]; exp: number
  login: (token: string) => void; logout: () => void
}

function decode(token: string) {
  const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
  return JSON.parse(new TextDecoder().decode(bytes))
}

export const useAuth = create<AuthState>()(persist((set) => ({
  token: null, userId: null, roles: [], exp: 0,
  login: (token) => { const p = decode(token); set({ token, userId: p.sub, roles: p.roles ?? [], exp: p.exp }) },
  logout: () => set({ token: null, userId: null, roles: [], exp: 0 }),
}), { name: 'auth' }))

export const isLoggedIn = (s: AuthState) => !!s.token && s.exp * 1000 > Date.now()
export const isAdmin = (s: AuthState) => isLoggedIn(s) && s.roles.includes('ADMIN')

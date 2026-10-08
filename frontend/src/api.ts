import { useQuery } from '@tanstack/react-query'
import { useAuth, isLoggedIn } from './auth'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}

type Opts = { method?: string; body?: unknown; headers?: Record<string, string> }

export async function api<T = any>(url: string, { method = 'GET', body, headers = {} }: Opts = {}): Promise<T> {
  const token = useAuth.getState().token
  const res = await fetch(url, {
    method,
    headers: {
      ...(body !== undefined && { 'Content-Type': 'application/json' }),
      ...(token && { Authorization: `Bearer ${token}` }),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) {
    if (res.status === 401) useAuth.getState().logout()
    let err: { code?: string; message?: string } = {}
    try { err = JSON.parse(text) } catch { /* non-JSON error body */ }
    throw new ApiError(res.status, err.code ?? `HTTP_${res.status}`, err.message ?? `Request failed (${res.status})`)
  }
  return text ? JSON.parse(text) : (undefined as T)
}

export type Wallet = { userId: string; balance: number; reserved: number; available: number }

export function useWallet() {
  const loggedIn = useAuth(isLoggedIn)
  return useQuery({ queryKey: ['wallet'], queryFn: () => api<Wallet>('/api/wallets/me'), enabled: loggedIn })
}

export const fmtOdds = (o: number | null) => o?.toFixed(2) ?? '—'

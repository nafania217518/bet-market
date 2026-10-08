import { Client } from '@stomp/stompjs'
import type { QueryClient } from '@tanstack/react-query'
import { create } from 'zustand'
import { useAuth, isLoggedIn } from './auth'

export const useToasts = create<{ toasts: string[]; push: (t: string) => void; dismiss: (i: number) => void }>((set) => ({
  toasts: [],
  push: (t) => set((s) => ({ toasts: [...s.toasts, t] })),
  dismiss: (i) => set((s) => ({ toasts: s.toasts.filter((_, j) => j !== i) })),
}))

// ponytail: the projection is async; the push can arrive before the read model updates, so wait 1 s.
const later = (fn: () => void) => setTimeout(fn, 1000)

function connect(qc: QueryClient) {
  const auth = useAuth.getState()
  const token = isLoggedIn(auth) ? auth.token : null
  const client = new Client({
    brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    onConnect: () => {
      const on = (dest: string, cb: (m: any) => void) => client.subscribe(dest, (f) => cb(JSON.parse(f.body)))
      on('/topic/public.markets', (m) => later(() => {
        qc.invalidateQueries({ queryKey: ['markets'] })
        qc.invalidateQueries({ queryKey: ['market', m.marketId] })
      }))
      on('/topic/public.leaderboard', () => later(() => qc.invalidateQueries({ queryKey: ['leaderboard'] })))
      if (!token) return
      on('/user/queue/wallet', (m) => {
        qc.setQueryData(['wallet'], (old: object | undefined) => ({ ...old, balance: m.balance, reserved: m.reserved, available: m.available }))
        qc.invalidateQueries({ queryKey: ['ledger'] })
      })
      on('/user/queue/notifications', (m) => {
        useToasts.getState().push(m.message ?? m.type)
        later(() => qc.invalidateQueries({ queryKey: ['myBets'] }))
      })
    },
  })
  client.activate()
  return client
}

/** Connects and reconnects on token change; returns a stop function. */
export function startLive(qc: QueryClient) {
  let client = connect(qc)
  const unsub = useAuth.subscribe((s, prev) => {
    if (s.token === prev.token) return
    client.deactivate()
    client = connect(qc)
  })
  return () => { unsub(); client.deactivate() }
}

import { QueryClient } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { useAuth } from './auth'
import { startLive, useToasts } from './live'
import { loginAs } from './test-utils'

const clients: any[] = []
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    subs: Record<string, (m: { body: string }) => void> = {}
    activate = vi.fn(); deactivate = vi.fn()
    constructor(public config: any) { clients.push(this) }
    subscribe(dest: string, cb: any) { this.subs[dest] = cb }
  },
}))

let stop: () => void
const qc = new QueryClient()
const connect = () => { stop = startLive(qc); const c = clients.at(-1); c.config.onConnect(); return c }
const send = (c: any, dest: string, body: object) => c.subs[dest]({ body: JSON.stringify(body) })

beforeEach(() => { clients.length = 0; useAuth.getState().logout(); useToasts.setState({ toasts: [] }); vi.useFakeTimers() })
afterEach(() => { stop(); vi.useRealTimers() })

test('live connects to ws://<host>/ws and activates', () => {
  const c = connect()
  expect(c.config.brokerURL).toBe(`ws://${location.host}/ws`)
  expect(c.activate).toHaveBeenCalled()
})

test('anonymous live sends no Authorization header and subscribes to public topics only', () => {
  const c = connect()
  expect(c.config.connectHeaders).toEqual({})
  expect(Object.keys(c.subs).sort()).toEqual(['/topic/public.leaderboard', '/topic/public.markets'])
})

test('logged-in live sends the Bearer header and subscribes to user queues', () => {
  loginAs()
  const c = connect()
  expect(c.config.connectHeaders).toEqual({ Authorization: `Bearer ${useAuth.getState().token}` })
  expect(Object.keys(c.subs).sort()).toEqual(['/topic/public.leaderboard', '/topic/public.markets', '/user/queue/notifications', '/user/queue/wallet'])
})

test('live reconnects when the token changes', () => {
  const c = connect()
  loginAs()
  expect(c.deactivate).toHaveBeenCalled()
  expect(clients).toHaveLength(2)
  expect(clients[1].config.connectHeaders.Authorization).toMatch(/^Bearer /)
})

test('a market message invalidates markets and that market after 1 s', () => {
  const spy = vi.spyOn(qc, 'invalidateQueries')
  send(connect(), '/topic/public.markets', { type: 'MarketUpdated', marketId: 'm1' })
  expect(spy).not.toHaveBeenCalled()
  vi.advanceTimersByTime(1000)
  expect(spy).toHaveBeenCalledWith({ queryKey: ['markets'] })
  expect(spy).toHaveBeenCalledWith({ queryKey: ['market', 'm1'] })
})

test('a leaderboard message invalidates the leaderboard after 1 s', () => {
  const spy = vi.spyOn(qc, 'invalidateQueries')
  send(connect(), '/topic/public.leaderboard', { type: 'LeaderboardUpdated' })
  vi.advanceTimersByTime(1000)
  expect(spy).toHaveBeenCalledWith({ queryKey: ['leaderboard'] })
})

test('a wallet message writes balances into the wallet cache and invalidates the ledger', () => {
  loginAs()
  const spy = vi.spyOn(qc, 'invalidateQueries')
  send(connect(), '/user/queue/wallet', { type: 'WalletChanged', balance: 10, reserved: 3, available: 7 })
  expect(qc.getQueryData(['wallet'])).toMatchObject({ balance: 10, reserved: 3, available: 7 })
  expect(spy).toHaveBeenCalledWith({ queryKey: ['ledger'] })
})

test('a notification pushes a toast and invalidates my bets after 1 s', () => {
  loginAs()
  const spy = vi.spyOn(qc, 'invalidateQueries')
  send(connect(), '/user/queue/notifications', { type: 'BetWon', message: 'You won 20' })
  expect(useToasts.getState().toasts).toEqual(['You won 20'])
  vi.advanceTimersByTime(1000)
  expect(spy).toHaveBeenCalledWith({ queryKey: ['myBets'] })
})

test('a notification without message shows its type as the toast', () => {
  loginAs()
  send(connect(), '/user/queue/notifications', { type: 'BetRefunded' })
  expect(useToasts.getState().toasts).toEqual(['BetRefunded'])
})

test('toasts can be dismissed', () => {
  useToasts.setState({ toasts: ['a', 'b'] })
  useToasts.getState().dismiss(0)
  expect(useToasts.getState().toasts).toEqual(['b'])
})

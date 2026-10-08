import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, fmtOdds, useWallet, type Wallet } from '../api'
import { isAdmin, isLoggedIn, useAuth } from '../auth'
import type { Market } from './Markets'
import type { Bet } from './MyBets'

type BetPage = { items: Bet[] }

export default function MarketDetail({ id }: { id: string }) {
  const qc = useQueryClient()
  const loggedIn = useAuth(isLoggedIn)
  const admin = useAuth(isAdmin)
  const userId = useAuth((s) => s.userId)
  const [error, setError] = useState('')
  const { data: m } = useQuery({ queryKey: ['market', id], queryFn: () => api<Market>(`/api/markets/${id}`) })
  const { data: myBets } = useQuery({
    queryKey: ['myBets'], queryFn: () => api<BetPage>('/api/me/bets?size=100'), enabled: loggedIn,
  })
  useWallet()

  /** Snapshot caches, apply an optimistic change, and return a rollback. */
  async function optimistic(changeBets: (b: Bet[]) => Bet[], walletDelta = 0) {
    await qc.cancelQueries({ queryKey: ['myBets'] })
    const bets = qc.getQueryData<BetPage>(['myBets'])
    const wallet = qc.getQueryData<Wallet>(['wallet'])
    qc.setQueryData<BetPage>(['myBets'], (p) => ({ ...p, items: changeBets(p?.items ?? []) }))
    if (wallet) qc.setQueryData<Wallet>(['wallet'], { ...wallet, available: wallet.available + walletDelta })
    return () => { qc.setQueryData(['myBets'], bets); qc.setQueryData(['wallet'], wallet) }
  }
  const refresh = () => { qc.invalidateQueries({ queryKey: ['myBets'] }); qc.invalidateQueries({ queryKey: ['wallet'] }) }
  const fail = (err: Error, _v: unknown, rollback?: () => void) => { rollback?.(); setError(err.message) }

  const place = useMutation({
    mutationFn: (b: { side: string; amount: number; tempId: string }) => api<{ betId: string; status: string }>('/api/bets', {
      method: 'POST', body: { marketId: id, side: b.side, amount: b.amount }, headers: { 'Idempotency-Key': crypto.randomUUID() },
    }),
    onMutate: (b) => {
      setError('')
      const temp: Bet = { betId: b.tempId, marketId: id, userId: userId!, side: b.side, amount: b.amount, status: 'PENDING', placedAt: new Date().toISOString() }
      return optimistic((items) => [temp, ...items], -b.amount)
    },
    onError: fail,
    onSuccess: (r, b) => {
      qc.setQueryData<BetPage>(['myBets'], (p) => p && { ...p, items: p.items.map((x) => x.betId === b.tempId ? { ...x, ...r } : x) })
      refresh()
    },
  })

  const cancel = useMutation({
    mutationFn: (betId: string) => api(`/api/bets/${betId}`, { method: 'DELETE' }),
    onMutate: (betId) => { setError(''); return optimistic((items) => items.map((x) => x.betId === betId ? { ...x, status: 'CANCELLED' } : x)) },
    onError: fail,
    onSuccess: refresh,
  })

  const adminAction = useMutation({
    mutationFn: ([url, method, body]: [string, string, unknown?]) => api(url, { method, body }),
    onMutate: () => setError(''),
    onError: (err) => setError(err.message),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['market', id] }); qc.invalidateQueries({ queryKey: ['markets'] }) },
  })

  if (!m) return <p>Loading…</p>
  const open = m.status === 'OPEN'
  const mine = myBets?.items.filter((b) => b.marketId === id) ?? []

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    place.mutate({ side: String(f.get('side')), amount: Number(f.get('amount')), tempId: `temp-${crypto.randomUUID()}` })
  }

  const btn = 'border rounded px-2 py-1'
  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">{m.title}</h2>
      <p>{m.description}</p>
      <p>Status: {m.status}{m.winningSide && ` · Winner: ${m.winningSide}`}</p>
      <p>Freezes {new Date(m.freezeAt).toLocaleString()} · Ends {new Date(m.endAt).toLocaleString()}</p>
      <p>TRUE pool: {m.truePool} · FALSE pool: {m.falsePool} · Total: {m.totalPool} · Bets: {m.betCount} · Bettors: {m.uniqueBettors}</p>
      <p>Odds: {fmtOdds(m.oddsTrue)} / {fmtOdds(m.oddsFalse)}</p>
      {error && <p role="alert" className="text-red-600">{error}</p>}

      {loggedIn && open && (
        <form onSubmit={submit} className="flex gap-2 items-end">
          <label>Side <select name="side" className="border rounded"><option>TRUE</option><option>FALSE</option></select></label>
          <label>Amount <input name="amount" type="number" step="1" min={m.minBet} max={m.maxBet} defaultValue={m.minBet} required className="border rounded px-2 w-28" /></label>
          <button className="bg-blue-600 text-white rounded px-3 py-1">Place bet</button>
        </form>
      )}

      {loggedIn && (
        <ul aria-label="My bets" className="space-y-1">
          {mine.map((b) => (
            <li key={b.betId}>
              {b.side} · {b.amount} · <span>{b.status}</span>{' '}
              {open && b.status === 'ACCEPTED' && <button className={btn} onClick={() => cancel.mutate(b.betId)}>Cancel</button>}
            </li>
          ))}
        </ul>
      )}

      {admin && (
        <div className="flex gap-2">
          {open && <button className={btn} onClick={() => adminAction.mutate([`/api/markets/${id}/freeze`, 'POST'])}>Freeze</button>}
          {m.status === 'ENDED' && ['TRUE', 'FALSE'].map((s) => (
            <button key={s} className={btn} onClick={() => adminAction.mutate([`/api/markets/${id}/resolve`, 'POST', { winningSide: s }])}>Resolve {s}</button>
          ))}
          {!['RESOLVED', 'CANCELLED'].includes(m.status) && (
            <button className={`${btn} text-red-600`} onClick={() => adminAction.mutate([`/api/markets/${id}`, 'DELETE'])}>Cancel market</button>
          )}
        </div>
      )}
    </div>
  )
}

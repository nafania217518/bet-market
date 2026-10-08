import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, fmtOdds, useWallet, type Wallet } from '../api'
import { isAdmin, isLoggedIn, useAuth } from '../auth'
import { OddsChip, type Market } from './Markets'
import { BetPill, btn, btnPrimary, card, ErrorBox, h1, input, MarketPill, muted, PoolBar, Skeleton } from '../ui'
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

  if (!m) return <Skeleton className="h-64" />
  const open = m.status === 'OPEN'
  const mine = myBets?.items.filter((b) => b.marketId === id) ?? []
  const fmt = (t: string) => new Date(t).toLocaleString()

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <section className={`${card} space-y-5 p-6 lg:col-span-2`}>
        <div className="flex flex-wrap items-center gap-2">
          <MarketPill status={m.status} />
          {m.winningSide && <span className="text-sm font-semibold">Winner: {m.winningSide}</span>}
        </div>
        <div>
          <h2 className={`${h1} mb-2 text-3xl`}>{m.title}</h2>
          <p className={muted}>{m.description}</p>
        </div>
        <p className={`text-sm ${muted}`}>Freezes {fmt(m.freezeAt)} · Ends {fmt(m.endAt)}</p>
        <div className="space-y-3">
          <div className="flex items-end justify-between">
            <div>
              <p className={`text-xs uppercase tracking-wide ${muted}`}>Total pool</p>
              <p className="text-4xl font-bold">{m.totalPool}</p>
            </div>
            <p className={`text-sm ${muted}`}>{m.betCount} bets · {m.uniqueBettors} bettors</p>
          </div>
          <PoolBar big t={m.truePool} f={m.falsePool} />
          <div className="flex flex-wrap justify-between gap-2 text-sm">
            <span className="flex items-center gap-2"><span className="font-semibold text-emerald-700 dark:text-emerald-300">TRUE pool: {m.truePool}</span><OddsChip side="TRUE" odds={m.oddsTrue} /></span>
            <span className="flex items-center gap-2"><OddsChip side="FALSE" odds={m.oddsFalse} /><span className="font-semibold text-rose-700 dark:text-rose-300">FALSE pool: {m.falsePool}</span></span>
          </div>
        </div>
      </section>

      <div className="space-y-6">
        {error && <ErrorBox>{error}</ErrorBox>}
        {loggedIn && open && <BetForm m={m} busy={place.isPending} onPlace={(side, amount) =>
          place.mutate({ side, amount, tempId: `temp-${crypto.randomUUID()}` })} />}

        {loggedIn && (
          <section className={`${card} p-5`}>
            <h3 className="mb-3 font-semibold">My bets on this market</h3>
            {mine.length === 0 && <p className={`text-sm ${muted}`}>No bets yet</p>}
            <ul aria-label="My bets" className="space-y-2">
              {mine.map((b) => (
                <li key={b.betId} className="flex items-center gap-2 rounded-xl bg-slate-100/80 px-3 py-2 text-sm dark:bg-white/5">
                  <BetPill status={b.side} /><span className="font-semibold">{b.amount}</span>
                  <span className="ml-auto"><BetPill status={b.status} /></span>
                  {open && b.status === 'ACCEPTED' && <button className={btn} onClick={() => cancel.mutate(b.betId)}>Cancel</button>}
                </li>
              ))}
            </ul>
          </section>
        )}

        {admin && (
          <section aria-label="Admin actions" className="rounded-2xl border border-dashed border-amber-400/70 bg-amber-50/60 p-5 dark:border-amber-400/30 dark:bg-amber-400/5">
            <h3 className="mb-3 text-sm font-semibold uppercase tracking-wide text-amber-900 dark:text-amber-200">Admin actions</h3>
            <div className="flex flex-wrap gap-2">
              {open && <button className={btn} onClick={() => adminAction.mutate([`/api/markets/${id}/freeze`, 'POST'])}>Freeze</button>}
              {m.status === 'ENDED' && ['TRUE', 'FALSE'].map((s) => (
                <button key={s} className={btn} onClick={() => adminAction.mutate([`/api/markets/${id}/resolve`, 'POST', { winningSide: s }])}>Resolve {s}</button>
              ))}
              {!['RESOLVED', 'CANCELLED'].includes(m.status) && (
                <button className={`${btn} text-rose-700 dark:text-rose-300`} onClick={() => adminAction.mutate([`/api/markets/${id}`, 'DELETE'])}>Cancel market</button>
              )}
            </div>
          </section>
        )}
      </div>
    </div>
  )
}

function BetForm({ m, busy, onPlace }: { m: Market; busy: boolean; onPlace: (side: string, amount: number) => void }) {
  const [side, setSide] = useState('TRUE')
  const [amount, setAmount] = useState(String(m.minBet))
  const clamp = (v: number) => Math.min(m.maxBet, Math.max(m.minBet, v))
  const chips: [string, number][] = [['Min', m.minBet], ['10', 10], ['50', 50], ['100', 100], ['Max', m.maxBet]]

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    onPlace(side, Number(amount))
  }

  return (
    <form onSubmit={submit} className={`${card} space-y-4 p-5`}>
      <h3 className="font-semibold">Place a bet</h3>
      <div role="group" aria-label="Side" className="grid grid-cols-2 gap-3">
        {['TRUE', 'FALSE'].map((s) => (
          <button key={s} type="button" aria-pressed={side === s} onClick={() => setSide(s)}
            className={`rounded-xl border-2 py-4 text-lg font-bold transition ${side === s
              ? s === 'TRUE' ? 'border-emerald-600 bg-emerald-600 text-white shadow-lg shadow-emerald-600/20' : 'border-rose-600 bg-rose-600 text-white shadow-lg shadow-rose-600/20'
              : 'border-slate-300 text-slate-700 hover:border-slate-400 dark:border-slate-700 dark:text-slate-300'}`}>{s}</button>
        ))}
      </div>
      <label className="block text-sm font-medium">Amount
        <input name="amount" type="number" step="1" min={m.minBet} max={m.maxBet} value={amount}
          onChange={(e) => setAmount(e.target.value)} required className={`${input} text-lg font-semibold`} />
      </label>
      <div role="group" aria-label="Quick amounts" className="flex flex-wrap gap-2">
        {chips.map(([label, v]) => (
          <button key={label} type="button" onClick={() => setAmount(String(clamp(v)))}
            className="rounded-full border border-slate-300 px-3 py-1 text-xs font-semibold text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">{label}</button>
        ))}
      </div>
      <p className={`text-xs ${muted}`}>Min {m.minBet} · Max {m.maxBet}</p>
      <button disabled={busy} aria-busy={busy} className={`${btnPrimary} w-full py-3 text-base`}>
        {busy && <span aria-hidden className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" />}
        Place bet
      </button>
    </form>
  )
}

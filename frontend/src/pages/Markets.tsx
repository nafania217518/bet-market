import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, fmtOdds } from '../api'
import { card, Empty, h1, MarketPill, muted, PoolBar, Segmented, Skeleton, timeLabel } from '../ui'

export type Market = {
  marketId: string; title: string; description: string; status: string; winningSide: string | null
  freezeAt: string; endAt: string; minBet: number; maxBet: number; truePool: number; falsePool: number
  totalPool: number; betCount: number; uniqueBettors: number; oddsTrue: number | null; oddsFalse: number | null
}

export const OddsChip = ({ side, odds }: { side: string; odds: number | null }) => (
  <span className={`rounded-lg px-2 py-1 text-xs font-semibold ${side === 'TRUE'
    ? 'bg-emerald-50 text-emerald-800 dark:bg-emerald-500/10 dark:text-emerald-300'
    : 'bg-rose-50 text-rose-800 dark:bg-rose-500/10 dark:text-rose-300'}`}>{side} {fmtOdds(odds)}</span>
)

function MarketCard({ m }: { m: Market }) {
  return (
    <article className={`${card} flex flex-col gap-4 p-5 transition hover:-translate-y-0.5 hover:shadow-lg motion-reduce:hover:translate-y-0`}>
      <div className="flex items-center justify-between gap-2">
        <MarketPill status={m.status} />
        <span className={`text-xs ${muted}`}>{timeLabel(m.status, m.freezeAt)}</span>
      </div>
      <a className="text-lg font-semibold leading-snug hover:text-indigo-700 hover:underline dark:hover:text-indigo-300" href={`#/markets/${m.marketId}`}>{m.title}</a>
      <div className="mt-auto">
        <p className={`text-xs uppercase tracking-wide ${muted}`}>Total pool</p>
        <p className="text-3xl font-bold">{m.totalPool}</p>
      </div>
      <PoolBar t={m.truePool} f={m.falsePool} />
      <div className="flex gap-2"><OddsChip side="TRUE" odds={m.oddsTrue} /><OddsChip side="FALSE" odds={m.oddsFalse} /></div>
    </article>
  )
}

export default function Markets() {
  const [status, setStatus] = useState('OPEN')
  const [sort, setSort] = useState('endAt')
  const q = new URLSearchParams({ ...(status !== 'all' && { status }), sort, size: '50' })
  const { data } = useQuery({
    queryKey: ['markets', status, sort],
    queryFn: () => api<{ items: Market[] }>(`/api/markets?${q}`),
  })
  return (
    <div>
      <h2 className={h1}>Markets</h2>
      <div className="mb-6 flex flex-wrap gap-3">
        <Segmented label="Status" value={status} onChange={setStatus} options={['OPEN', 'FROZEN', 'ENDED', 'RESOLVED', 'CANCELLED', 'all']} />
        <Segmented label="Sort" value={sort} onChange={setSort} options={['endAt', 'pool']} />
      </div>
      {data?.items.length === 0 && <Empty>No markets here yet</Empty>}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {data ? data.items.map((m) => <MarketCard key={m.marketId} m={m} />) : [1, 2, 3].map((i) => <Skeleton key={i} className="h-56" />)}
      </div>
    </div>
  )
}

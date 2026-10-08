import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { card, Empty, h1, muted, row, Segmented, Skeleton, table } from '../ui'

type Entry = { rank: number; userId: string; displayName: string; netProfit: number }

const signed = (n: number) => (n > 0 ? `+${n}` : String(n))
const profitColor = (n: number) => (n > 0 ? 'text-emerald-700 dark:text-emerald-300' : n < 0 ? 'text-rose-700 dark:text-rose-300' : '')
const medals = [
  'from-amber-300 to-yellow-500 text-amber-950 ring-amber-400/60', // gold
  'from-slate-200 to-slate-400 text-slate-900 ring-slate-400/60', // silver
  'from-orange-300 to-amber-700 text-orange-950 ring-orange-500/60', // bronze
]
const heights = ['sm:pt-2', 'sm:pt-8', 'sm:pt-12']

export default function Leaderboard() {
  const [win, setWin] = useState('all')
  const { data } = useQuery({
    queryKey: ['leaderboard', win],
    queryFn: () => api<{ entries: Entry[] }>(`/api/leaderboard?window=${win}&limit=50`),
  })
  const top = data?.entries.slice(0, 3) ?? []
  const rest = data?.entries.slice(3) ?? []
  return (
    <div>
      <h2 className={h1}>Leaderboard</h2>
      <div className="mb-6"><Segmented label="Window" value={win} onChange={setWin} options={['all', '7d', '30d']} /></div>
      {!data && <Skeleton className="h-48" />}
      {data?.entries.length === 0 && <Empty>No winners yet. The board fills up as markets resolve.</Empty>}
      {top.length > 0 && (
        <ol aria-label="Top 3" className="mb-6 grid items-end gap-4 sm:grid-cols-3">
          {top.map((e, i) => (
            <li key={e.userId} className={`${heights[i]} ${i === 0 ? 'sm:order-2' : i === 1 ? 'sm:order-1' : 'sm:order-3'}`}>
              <div className={`${card} p-5 text-center`}>
                <span className={`mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br font-bold ring-2 ${medals[i]}`}>{e.rank}</span>
                <p className="truncate font-semibold">{e.displayName}</p>
                <p className={`text-2xl font-bold ${profitColor(e.netProfit)}`}>{signed(e.netProfit)}</p>
              </div>
            </li>
          ))}
        </ol>
      )}
      {rest.length > 0 && (
        <div className={`${card} overflow-x-auto`}>
          <table className={table}>
            <thead><tr><th>Rank</th><th>Name</th><th className="text-right">Net profit</th></tr></thead>
            <tbody>
              {rest.map((e) => (
                <tr key={e.userId} className={row}>
                  <td className={muted}>{e.rank}</td><td className="font-medium">{e.displayName}</td>
                  <td className={`text-right font-semibold ${profitColor(e.netProfit)}`}>{signed(e.netProfit)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

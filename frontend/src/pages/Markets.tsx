import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, fmtOdds } from '../api'

export type Market = {
  marketId: string; title: string; description: string; status: string; winningSide: string | null
  freezeAt: string; endAt: string; minBet: number; maxBet: number; truePool: number; falsePool: number
  totalPool: number; betCount: number; uniqueBettors: number; oddsTrue: number | null; oddsFalse: number | null
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
      <h2 className="text-xl font-bold mb-2">Markets</h2>
      <div className="flex gap-4 mb-2">
        <label>Status <select value={status} onChange={(e) => setStatus(e.target.value)} className="border rounded">
          {['OPEN', 'FROZEN', 'ENDED', 'RESOLVED', 'CANCELLED', 'all'].map((s) => <option key={s}>{s}</option>)}
        </select></label>
        <label>Sort <select value={sort} onChange={(e) => setSort(e.target.value)} className="border rounded">
          <option>endAt</option><option>pool</option>
        </select></label>
      </div>
      <table className="w-full text-left">
        <thead><tr><th>Title</th><th>Status</th><th>Pool</th><th>Odds T / F</th><th>Freezes</th></tr></thead>
        <tbody>
          {data?.items.map((m) => (
            <tr key={m.marketId} className="border-t">
              <td><a className="text-blue-600 underline" href={`#/markets/${m.marketId}`}>{m.title}</a></td>
              <td>{m.status}</td>
              <td>{m.totalPool}</td>
              <td>{fmtOdds(m.oddsTrue)} / {fmtOdds(m.oddsFalse)}</td>
              <td>{new Date(m.freezeAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

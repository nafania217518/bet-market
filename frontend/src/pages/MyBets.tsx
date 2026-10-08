import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { isLoggedIn, useAuth } from '../auth'

export type Bet = { betId: string; marketId: string; userId: string; side: string; amount: number; status: string; placedAt: string }

export default function MyBets() {
  const loggedIn = useAuth(isLoggedIn)
  const [status, setStatus] = useState('')
  const { data } = useQuery({
    queryKey: ['myBets', status],
    queryFn: () => api<{ items: Bet[] }>(`/api/me/bets?${new URLSearchParams({ ...(status && { status }), size: '50' })}`),
    enabled: loggedIn,
  })
  if (!loggedIn) return <a className="text-blue-600 underline" href="#/login">Log in to see your bets</a>
  return (
    <div>
      <h2 className="text-xl font-bold mb-2">My bets</h2>
      <label>Status <select value={status} onChange={(e) => setStatus(e.target.value)} className="border rounded mb-2">
        <option value="">all</option>
        {['ACCEPTED', 'CANCELLED', 'WON', 'LOST', 'REFUNDED'].map((s) => <option key={s}>{s}</option>)}
      </select></label>
      <table className="w-full text-left">
        <thead><tr><th>Market</th><th>Side</th><th>Amount</th><th>Status</th><th>Placed</th></tr></thead>
        <tbody>
          {data?.items.map((b) => (
            <tr key={b.betId} className="border-t">
              <td><a className="text-blue-600 underline" href={`#/markets/${b.marketId}`}>{b.marketId}</a></td>
              <td>{b.side}</td><td>{b.amount}</td><td>{b.status}</td><td>{new Date(b.placedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { isLoggedIn, useAuth } from '../auth'
import { BetPill, card, Empty, h1, link, LoginPrompt, muted, row, Segmented, Skeleton, table } from '../ui'

export type Bet = { betId: string; marketId: string; userId: string; side: string; amount: number; status: string; placedAt: string }

export default function MyBets() {
  const loggedIn = useAuth(isLoggedIn)
  const [status, setStatus] = useState('all')
  const { data } = useQuery({
    queryKey: ['myBets', status],
    queryFn: () => api<{ items: Bet[] }>(`/api/me/bets?${new URLSearchParams({ ...(status !== 'all' && { status }), size: '50' })}`),
    enabled: loggedIn,
  })
  if (!loggedIn) return <LoginPrompt>Log in to see your bets</LoginPrompt>
  return (
    <div>
      <h2 className={h1}>My bets</h2>
      <div className="mb-6"><Segmented label="Status" value={status} onChange={setStatus} options={['all', 'ACCEPTED', 'CANCELLED', 'WON', 'LOST', 'REFUNDED']} /></div>
      {!data ? <Skeleton className="h-40" /> : data.items.length === 0 ? <Empty>No bets yet. Pick a market and place your first bet.</Empty> : (
        <div className={`${card} overflow-x-auto`}>
          <table className={table}>
            <thead><tr><th>Market</th><th>Side</th><th className="text-right">Amount</th><th>Status</th><th>Placed</th></tr></thead>
            <tbody>
              {data.items.map((b) => (
                <tr key={b.betId} className={row}>
                  <td><a className={link} href={`#/markets/${b.marketId}`}>{b.marketId}</a></td>
                  <td><BetPill status={b.side} /></td>
                  <td className="text-right font-semibold">{b.amount}</td>
                  <td><BetPill status={b.status} /></td>
                  <td className={muted}>{new Date(b.placedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

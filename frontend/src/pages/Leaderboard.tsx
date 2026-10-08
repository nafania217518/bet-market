import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'

type Entry = { rank: number; userId: string; displayName: string; netProfit: number }

export default function Leaderboard() {
  const [win, setWin] = useState('all')
  const { data } = useQuery({
    queryKey: ['leaderboard', win],
    queryFn: () => api<{ entries: Entry[] }>(`/api/leaderboard?window=${win}&limit=50`),
  })
  return (
    <div>
      <h2 className="text-xl font-bold mb-2">Leaderboard</h2>
      <div className="flex gap-2 mb-2">
        {['all', '7d', '30d'].map((w) => (
          <button key={w} onClick={() => setWin(w)} className={`border rounded px-2 ${w === win ? 'bg-blue-600 text-white' : ''}`}>{w}</button>
        ))}
      </div>
      <table className="w-full text-left">
        <thead><tr><th>Rank</th><th>Name</th><th>Net profit</th></tr></thead>
        <tbody>
          {data?.entries.map((e) => <tr key={e.userId} className="border-t"><td>{e.rank}</td><td>{e.displayName}</td><td>{e.netProfit}</td></tr>)}
        </tbody>
      </table>
    </div>
  )
}

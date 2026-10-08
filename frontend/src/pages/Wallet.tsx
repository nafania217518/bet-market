import { useQuery } from '@tanstack/react-query'
import { api, useWallet } from '../api'
import { isLoggedIn, useAuth } from '../auth'

type Entry = { id: string; betId: string | null; type: string; amount: number; balanceAfter: number; createdAt: string }

export default function Wallet() {
  const loggedIn = useAuth(isLoggedIn)
  const { data: w } = useWallet()
  const { data: ledger } = useQuery({
    queryKey: ['ledger'],
    queryFn: () => api<{ entries: Entry[] }>('/api/wallets/me/ledger?page=0&size=50'),
    enabled: loggedIn,
  })
  if (!loggedIn) return <a className="text-blue-600 underline" href="#/login">Log in to see your wallet</a>
  return (
    <div>
      <h2 className="text-xl font-bold mb-2">Wallet</h2>
      {w && <div className="flex gap-6 mb-4"><span>Balance: {w.balance}</span><span>Reserved: {w.reserved}</span><span>Available: {w.available}</span></div>}
      <table className="w-full text-left">
        <thead><tr><th>Time</th><th>Type</th><th>Amount</th><th>Balance after</th><th>Bet</th></tr></thead>
        <tbody>
          {ledger?.entries.map((e) => (
            <tr key={e.id} className="border-t">
              <td>{new Date(e.createdAt).toLocaleString()}</td><td>{e.type}</td><td>{e.amount}</td><td>{e.balanceAfter}</td><td>{e.betId}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

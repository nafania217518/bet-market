import { useQuery } from '@tanstack/react-query'
import { api, useWallet } from '../api'
import { isLoggedIn, useAuth } from '../auth'
import { card, Empty, h1, LoginPrompt, muted, row, Skeleton, table } from '../ui'

type Entry = { id: string; betId: string | null; type: string; amount: number; balanceAfter: number; createdAt: string }

const amountColor: Record<string, string> = { CREDIT: 'text-emerald-700 dark:text-emerald-300', DEBIT: 'text-rose-700 dark:text-rose-300' }

const Stat = ({ label, value, accent }: { label: string; value: number; accent: string }) => (
  <div role="group" aria-label={label} className={`${card} relative overflow-hidden p-5`}>
    <div className={`absolute inset-x-0 top-0 h-1 ${accent}`} />
    <p className={`text-xs font-semibold uppercase tracking-wide ${muted}`}>{label}</p>
    <p className="mt-1 text-3xl font-bold">{value}</p>
  </div>
)

export default function Wallet() {
  const loggedIn = useAuth(isLoggedIn)
  const { data: w } = useWallet()
  const { data: ledger } = useQuery({
    queryKey: ['ledger'],
    queryFn: () => api<{ entries: Entry[] }>('/api/wallets/me/ledger?page=0&size=50'),
    enabled: loggedIn,
  })
  if (!loggedIn) return <LoginPrompt>Log in to see your wallet</LoginPrompt>
  return (
    <div>
      <h2 className={h1}>Wallet</h2>
      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        {w ? <>
          <Stat label="Balance" value={w.balance} accent="bg-indigo-500" />
          <Stat label="Reserved" value={w.reserved} accent="bg-amber-500" />
          <Stat label="Available" value={w.available} accent="bg-emerald-500" />
        </> : [1, 2, 3].map((i) => <Skeleton key={i} />)}
      </div>
      {ledger?.entries.length === 0 ? <Empty>No wallet activity yet</Empty> : (
        <div className={`${card} overflow-x-auto`}>
          <table className={table}>
            <thead><tr><th>Time</th><th>Type</th><th className="text-right">Amount</th><th className="text-right">Balance after</th><th>Bet</th></tr></thead>
            <tbody>
              {ledger?.entries.map((e) => (
                <tr key={e.id} className={row}>
                  <td className={muted}>{new Date(e.createdAt).toLocaleString()}</td>
                  <td className="font-medium">{e.type}</td>
                  <td className={`text-right font-semibold ${amountColor[e.type] ?? ''}`}>{e.amount > 0 ? `+${e.amount}` : e.amount}</td>
                  <td className="text-right">{e.balanceAfter}</td>
                  <td className={`font-mono text-xs ${muted}`}>{e.betId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

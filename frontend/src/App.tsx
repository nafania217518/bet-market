import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useWallet } from './api'
import { isAdmin, isLoggedIn, useAuth } from './auth'
import { startLive, useToasts } from './live'
import { useHashRoute } from './route'
import Admin from './pages/Admin'
import Leaderboard from './pages/Leaderboard'
import Login from './pages/Login'
import MarketDetail from './pages/MarketDetail'
import Markets from './pages/Markets'
import MyBets from './pages/MyBets'
import Wallet from './pages/Wallet'

function Page() {
  const [page, id] = useHashRoute()
  if (page === 'markets' && id) return <MarketDetail id={id} />
  if (page === 'bets') return <MyBets />
  if (page === 'wallet') return <Wallet />
  if (page === 'leaderboard') return <Leaderboard />
  if (page === 'login') return <Login />
  if (page === 'admin') return <Admin />
  return <Markets />
}

export default function App() {
  const qc = useQueryClient()
  useEffect(() => startLive(qc), [qc])
  const loggedIn = useAuth(isLoggedIn)
  const admin = useAuth(isAdmin)
  const { data: wallet } = useWallet()
  const { toasts, dismiss } = useToasts()
  const link = 'hover:underline'

  return (
    <div className="max-w-5xl mx-auto p-4">
      <header className="flex flex-wrap gap-4 items-center border-b pb-2 mb-4">
        <a href="#/" className="font-bold">Bet Market</a>
        <a href="#/" className={link}>Markets</a>
        <a href="#/bets" className={link}>My bets</a>
        <a href="#/wallet" className={link}>Wallet</a>
        <a href="#/leaderboard" className={link}>Leaderboard</a>
        {admin && <a href="#/admin" className={link}>Admin</a>}
        <span className="ml-auto" />
        {loggedIn && wallet && <span>Available: {wallet.available}</span>}
        {loggedIn
          ? <button className="border rounded px-2" onClick={() => { useAuth.getState().logout(); qc.clear() }}>Log out</button>
          : <a href="#/login" className={link}>Log in</a>}
      </header>
      <main><Page /></main>
      <div className="fixed bottom-4 right-4 space-y-2">
        {toasts.map((t, i) => (
          <button key={i} onClick={() => dismiss(i)} className="block bg-gray-800 text-white rounded px-3 py-2 shadow">{t}</button>
        ))}
      </div>
    </div>
  )
}

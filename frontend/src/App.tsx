import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useWallet } from './api'
import { isLoggedIn, useAuth } from './auth'
import { startLive, useToasts } from './live'
import { useHashRoute } from './route'
import { setTheme, initTheme, useTheme } from './theme'
import { btn } from './ui'
import Leaderboard from './pages/Leaderboard'
import Login from './pages/Login'
import MarketDetail from './pages/MarketDetail'
import Markets from './pages/Markets'
import MyBets from './pages/MyBets'
import NewMarket from './pages/NewMarket'
import Wallet from './pages/Wallet'

/** Maps the hash route to a page key; '' is the markets list. */
function pageKey([page, id]: string[]) {
  if (page === 'admin' || (page === 'markets' && id === 'new')) return 'new'
  if (page === 'markets' && id) return 'market'
  return page ?? ''
}

function Page({ route }: { route: string[] }) {
  const key = pageKey(route)
  if (key === 'new') return <NewMarket />
  if (key === 'market') return <MarketDetail id={route[1]} />
  if (key === 'bets') return <MyBets />
  if (key === 'wallet') return <Wallet />
  if (key === 'leaderboard') return <Leaderboard />
  if (key === 'login') return <Login />
  return <Markets />
}

const Logo = () => (
  <svg viewBox="0 0 32 32" className="h-8 w-8" aria-hidden>
    <defs><linearGradient id="lg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#10b981" /><stop offset="1" stopColor="#6366f1" /></linearGradient></defs>
    <rect width="32" height="32" rx="9" fill="url(#lg)" />
    <path d="M8 22l5-6 4 3 7-9" fill="none" stroke="#fff" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

export const Coin = () => (
  <svg viewBox="0 0 20 20" className="h-4 w-4" aria-hidden>
    <circle cx="10" cy="10" r="8.5" fill="#f59e0b" stroke="#b45309" />
    <circle cx="10" cy="10" r="5" fill="none" stroke="#fde68a" strokeWidth="1.5" />
  </svg>
)

const Sun = () => (
  <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
    <circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </svg>
)

const Moon = () => (
  <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
  </svg>
)

function ThemeSwitch() {
  const dark = useTheme((s) => s.theme) === 'dark'
  const label = dark ? 'Switch to light theme' : 'Switch to dark theme'
  return (
    <button className={`${btn} py-1.5`} aria-label={label} title={label} onClick={() => setTheme(dark ? 'light' : 'dark')}>
      {dark ? <Sun /> : <Moon />}
    </button>
  )
}

export default function App() {
  const qc = useQueryClient()
  useEffect(() => startLive(qc), [qc])
  useEffect(initTheme, [])
  const loggedIn = useAuth(isLoggedIn)
  const { data: wallet } = useWallet()
  const { toasts, dismiss } = useToasts()
  const route = useHashRoute()
  const active = pageKey(route)

  const nav: [string, string, string][] = [['', '#/', 'Markets'], ['bets', '#/bets', 'My bets'], ['wallet', '#/wallet', 'Wallet'], ['leaderboard', '#/leaderboard', 'Leaderboard']]
  if (loggedIn) nav.push(['new', '#/markets/new', 'New market'])

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-20 border-b border-slate-200/70 bg-white/70 backdrop-blur-lg dark:border-white/10 dark:bg-slate-950/60">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
          <a href="#/" className="flex items-center gap-2">
            <Logo />
            <span className="bg-gradient-to-r from-emerald-600 to-indigo-600 bg-clip-text text-lg font-extrabold tracking-tight text-transparent dark:from-emerald-400 dark:to-indigo-400">Bet Market</span>
          </a>
          <nav className="order-last flex w-full gap-1 overflow-x-auto md:order-none md:w-auto">
            {nav.map(([key, href, label]) => (
              <a key={key} href={href} aria-current={active === key || (key === '' && active === 'market') ? 'page' : undefined}
                className="whitespace-nowrap rounded-full px-3 py-1.5 text-sm font-medium text-slate-700 transition hover:bg-slate-200/70 aria-[current=page]:bg-slate-900 aria-[current=page]:text-white dark:text-slate-300 dark:hover:bg-white/10 dark:aria-[current=page]:bg-white dark:aria-[current=page]:text-slate-900">
                {label}
              </a>
            ))}
          </nav>
          <span className="ml-auto" />
          {loggedIn && wallet && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-300/60 bg-amber-50 px-3 py-1 text-sm font-semibold text-amber-900 dark:border-amber-400/20 dark:bg-amber-400/10 dark:text-amber-200">
              <Coin /><span>Available: {wallet.available}</span>
            </span>
          )}
          <ThemeSwitch />
          {loggedIn
            ? <button className={btn} onClick={() => { useAuth.getState().logout(); qc.clear() }}>Log out</button>
            : <a href="#/login" className={btn}>Log in</a>}
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8"><Page route={route} /></main>
      <div className="fixed bottom-4 right-4 z-30 space-y-2">
        {toasts.map((t, i) => (
          <button key={i} onClick={() => dismiss(i)}
            className="toast-in block max-w-xs rounded-2xl border border-white/20 bg-slate-900/85 px-4 py-3 text-left text-sm text-white shadow-xl backdrop-blur-lg dark:bg-slate-800/85">{t}</button>
        ))}
      </div>
    </div>
  )
}

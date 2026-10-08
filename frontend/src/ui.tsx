import type { ReactNode } from 'react'

export const card = 'rounded-2xl border border-slate-200/80 bg-white/75 shadow-sm backdrop-blur dark:border-white/10 dark:bg-slate-900/60'
export const h1 = 'mb-5 text-2xl font-bold tracking-tight'
export const muted = 'text-slate-600 dark:text-slate-400'
export const input = 'mt-1 block w-full rounded-xl border border-slate-300 bg-white/90 px-3 py-2 text-slate-900 shadow-sm focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-500/40 dark:border-slate-700 dark:bg-slate-900/80 dark:text-slate-100'
export const btnPrimary = 'inline-flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-emerald-700 to-indigo-600 px-4 py-2 font-semibold text-white shadow-md shadow-emerald-900/10 transition hover:brightness-110 disabled:opacity-60'
export const btn = 'rounded-lg border border-slate-300 bg-white/70 px-3 py-1 text-sm font-medium text-slate-800 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-100 dark:hover:bg-slate-700'
export const table = 'w-full text-left text-sm [&_th]:px-4 [&_th]:py-2 [&_th]:text-xs [&_th]:font-semibold [&_th]:uppercase [&_th]:tracking-wide [&_th]:text-slate-600 dark:[&_th]:text-slate-400 [&_td]:px-4 [&_td]:py-2.5'
export const row = 'border-t border-slate-200/80 dark:border-white/5'
export const link = 'font-medium text-indigo-700 hover:underline dark:text-indigo-300'

const tones = {
  emerald: 'bg-emerald-100 text-emerald-800 ring-emerald-600/20 dark:bg-emerald-500/15 dark:text-emerald-300',
  amber: 'bg-amber-100 text-amber-900 ring-amber-600/20 dark:bg-amber-500/15 dark:text-amber-300',
  slate: 'bg-slate-200 text-slate-700 ring-slate-500/20 dark:bg-slate-500/20 dark:text-slate-300',
  indigo: 'bg-indigo-100 text-indigo-800 ring-indigo-600/20 dark:bg-indigo-500/20 dark:text-indigo-300',
  rose: 'bg-rose-100 text-rose-800 ring-rose-600/20 dark:bg-rose-500/15 dark:text-rose-300',
  sky: 'bg-sky-100 text-sky-800 ring-sky-600/20 dark:bg-sky-500/15 dark:text-sky-300',
}
type Tone = keyof typeof tones
const marketTones: Record<string, Tone> = { OPEN: 'emerald', FROZEN: 'amber', ENDED: 'slate', RESOLVED: 'indigo', CANCELLED: 'rose', TRUE: 'emerald', FALSE: 'rose' }
const betTones: Record<string, Tone> = { WON: 'emerald', LOST: 'rose', REFUNDED: 'sky', CANCELLED: 'slate', ACCEPTED: 'amber', PENDING: 'amber', TRUE: 'emerald', FALSE: 'rose' }

function Pill({ text, tone }: { text: string; tone: Tone }) {
  return <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset ${tones[tone]}`}>{text}</span>
}
export const MarketPill = ({ status }: { status: string }) => <Pill text={status} tone={marketTones[status] ?? 'slate'} />
export const BetPill = ({ status }: { status: string }) => <Pill text={status} tone={betTones[status] ?? 'slate'} />

export function PoolBar({ t, f, big = false }: { t: number; f: number; big?: boolean }) {
  const total = t + f
  const pt = total ? Math.round((t * 100) / total) : 0
  return (
    <div role="img" aria-label={total ? `TRUE ${pt}%, FALSE ${100 - pt}%` : 'No bets yet'}
      className={`flex gap-0.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800 ${big ? 'h-4' : 'h-2'}`}>
      {total > 0 && <>
        <div data-side="TRUE" style={{ width: `${(t * 100) / total}%` }} className="bg-gradient-to-r from-emerald-500 to-emerald-400" />
        <div data-side="FALSE" style={{ width: `${(f * 100) / total}%` }} className="bg-gradient-to-r from-rose-400 to-rose-500" />
      </>}
    </div>
  )
}

const closed: Record<string, string> = { FROZEN: 'Frozen', ENDED: 'Ended', RESOLVED: 'Resolved', CANCELLED: 'Cancelled' }

export function timeLabel(status: string, freezeAt: string, now = Date.now()) {
  if (status !== 'OPEN') return closed[status] ?? status
  const min = Math.floor((Date.parse(freezeAt) - now) / 60000)
  if (min < 0) return 'Freezing now'
  const d = Math.floor(min / 1440), h = Math.floor((min % 1440) / 60), m = min % 60
  return `Freezes in ${d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`}`
}

export function Segmented({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (v: string) => void }) {
  return (
    <div role="group" aria-label={label} className="inline-flex flex-wrap gap-1 rounded-xl border border-slate-200 bg-white/70 p-1 dark:border-white/10 dark:bg-slate-900/60">
      {options.map((o) => (
        <button key={o} type="button" aria-pressed={o === value} onClick={() => onChange(o)}
          className={`rounded-lg px-3 py-1 text-sm font-medium transition ${o === value
            ? 'bg-slate-900 text-white shadow dark:bg-white dark:text-slate-900'
            : 'text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'}`}>{o}</button>
      ))}
    </div>
  )
}

export const Skeleton = ({ className = 'h-24' }: { className?: string }) =>
  <div aria-hidden className={`animate-pulse rounded-2xl bg-slate-200/80 motion-reduce:animate-none dark:bg-slate-800/80 ${className}`} />

export const Empty = ({ children }: { children: ReactNode }) =>
  <div className={`${card} p-10 text-center ${muted}`}>{children}</div>

export const LoginPrompt = ({ children }: { children: ReactNode }) => (
  <div className={`${card} mx-auto max-w-md p-8 text-center`}>
    <a className={`${btnPrimary}`} href="#/login">{children}</a>
  </div>
)

export const ErrorBox = ({ children }: { children: ReactNode }) =>
  <p role="alert" className="rounded-xl border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200">{children}</p>

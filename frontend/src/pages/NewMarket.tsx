import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { isAdmin, isLoggedIn, useAuth } from '../auth'
import { btnPrimary, card, ErrorBox, h1, input, LoginPrompt, muted } from '../ui'
import type { Market } from './Markets'

export default function NewMarket() {
  const loggedIn = useAuth(isLoggedIn)
  const admin = useAuth(isAdmin)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  if (!loggedIn) return <LoginPrompt>Log in to create a market</LoginPrompt>

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setError(''); setBusy(true)
    const f = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>
    api<Market>('/api/markets', {
      method: 'POST',
      body: {
        title: f.title, description: f.description,
        freezeAt: new Date(f.freezeAt).toISOString(), endAt: new Date(f.endAt).toISOString(),
        minBet: Number(f.minBet), maxBet: Number(f.maxBet),
      },
    }).then((m) => { location.hash = `#/markets/${m.marketId}` }, (err) => { setError(err.message); setBusy(false) })
  }

  const label = 'block text-sm font-medium'
  return (
    <form onSubmit={submit} className={`${card} mx-auto max-w-xl space-y-4 p-6`}>
      <div>
        <h2 className={`${h1} mb-1`}>New market</h2>
        {!admin && <p className={`text-sm ${muted}`}>You can have up to 3 active markets</p>}
      </div>
      {error && <ErrorBox>{error}</ErrorBox>}
      <label className={label}>Title<input name="title" required className={input} /></label>
      <label className={label}>Description<textarea name="description" rows={3} className={input} /></label>
      <div className="grid gap-4 sm:grid-cols-2">
        <label className={label}>Freeze at<input name="freezeAt" type="datetime-local" required className={input} /></label>
        <label className={label}>End at<input name="endAt" type="datetime-local" required className={input} /></label>
        <label className={label}>Min bet<input name="minBet" type="number" min="0" step="1" defaultValue="1" required className={input} /></label>
        <label className={label}>Max bet<input name="maxBet" type="number" min="0" step="1" defaultValue="100" required className={input} /></label>
      </div>
      <button disabled={busy} aria-busy={busy} className={`${btnPrimary} w-full`}>Create market</button>
    </form>
  )
}

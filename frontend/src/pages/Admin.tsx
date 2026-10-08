import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { isAdmin, useAuth } from '../auth'
import type { Market } from './Markets'

const input = 'border rounded px-2 py-1 block w-full'

export default function Admin() {
  const admin = useAuth(isAdmin)
  const [error, setError] = useState('')
  if (!admin) return <p>Admins only</p>

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>
    api<Market>('/api/markets', {
      method: 'POST',
      body: {
        title: f.title, description: f.description,
        freezeAt: new Date(f.freezeAt).toISOString(), endAt: new Date(f.endAt).toISOString(),
        minBet: Number(f.minBet), maxBet: Number(f.maxBet),
      },
    }).then((m) => { location.hash = `#/markets/${m.marketId}` }, (err) => setError(err.message))
  }

  return (
    <form onSubmit={submit} className="space-y-2 max-w-md">
      <h2 className="text-xl font-bold">Create market</h2>
      {error && <p role="alert" className="text-red-600">{error}</p>}
      <label className="block">Title<input name="title" required className={input} /></label>
      <label className="block">Description<textarea name="description" className={input} /></label>
      <label className="block">Freeze at<input name="freezeAt" type="datetime-local" required className={input} /></label>
      <label className="block">End at<input name="endAt" type="datetime-local" required className={input} /></label>
      <label className="block">Min bet<input name="minBet" type="number" min="0" step="1" defaultValue="1" required className={input} /></label>
      <label className="block">Max bet<input name="maxBet" type="number" min="0" step="1" defaultValue="100" required className={input} /></label>
      <button className="bg-blue-600 text-white rounded px-3 py-1">Create market</button>
    </form>
  )
}

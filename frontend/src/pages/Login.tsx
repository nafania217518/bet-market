import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { btnPrimary, card, ErrorBox, input } from '../ui'

export default function Login() {
  const [tab, setTab] = useState<'Log in' | 'Register'>('Log in')
  const [error, setError] = useState('')

  async function logIn(email: string, password: string) {
    const r = await api<{ accessToken: string }>('/api/auth/login', { method: 'POST', body: { email, password } })
    useAuth.getState().login(r.accessToken)
    location.hash = '#/'
  }

  const submit = (fn: (f: Record<string, string>) => Promise<void>) => (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError('')
    fn(Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>).catch((err) => setError(err.message))
  }

  const register = tab === 'Register'
  const label = 'block text-sm font-medium'
  return (
    <div className={`${card} mx-auto mt-6 max-w-md p-6 sm:p-8`}>
      <div role="tablist" className="mb-6 grid grid-cols-2 gap-1 rounded-xl bg-slate-100 p-1 dark:bg-slate-800/80">
        {(['Log in', 'Register'] as const).map((t) => (
          <button key={t} role="tab" type="button" aria-selected={tab === t} onClick={() => { setTab(t); setError('') }}
            className="rounded-lg py-2 text-sm font-semibold text-slate-600 transition aria-selected:bg-white aria-selected:text-slate-900 aria-selected:shadow dark:text-slate-400 dark:aria-selected:bg-slate-950 dark:aria-selected:text-white">{t}</button>
        ))}
      </div>
      <form key={tab} role="tabpanel" aria-label={tab} className="space-y-4" onSubmit={submit(register
        ? async (f) => {
          await api('/api/auth/register', { method: 'POST', body: { email: f.email, password: f.password, displayName: f.displayName } })
          await logIn(f.email, f.password)
        }
        : (f) => logIn(f.email, f.password))}>
        <h2 className="text-2xl font-bold tracking-tight">{tab}</h2>
        {error && <ErrorBox>{error}</ErrorBox>}
        <label className={label}>Email<input name="email" type="email" required className={input} /></label>
        <label className={label}>Password<input name="password" type="password" required className={input} /></label>
        {register && <label className={label}>Display name<input name="displayName" required className={input} /></label>}
        <button className={`${btnPrimary} w-full`}>{tab}</button>
      </form>
    </div>
  )
}

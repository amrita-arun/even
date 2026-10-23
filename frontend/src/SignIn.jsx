import { useState } from 'react'
import { supabase } from './supabase'

export default function SignIn({ onSignedIn, onBack }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')
    setLoading(true)
    const { error: signInError } = await supabase.auth.signInWithPassword({
      email,
      password,
    })
    setLoading(false)
    if (signInError) {
      setError(signInError.message)
      return
    }
    onSignedIn()
  }

  return (
    <main className="signin">
      {onBack ? (
        <button className="text-btn" type="button" onClick={onBack}>
          Back
        </button>
      ) : null}
      <p className="wordmark">even</p>
      <h1>Sign in</h1>
      <p className="lede">Sign in to continue.</p>
      <form onSubmit={handleSubmit} className="stack-form">
        <label className="stack-field">
          Email
          <input
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </label>
        <label className="stack-field">
          Password
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>
        {error ? <p className="banner banner-error" role="alert">{error}</p> : null}
        <button className="btn primary" type="submit" disabled={loading}>
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  )
}

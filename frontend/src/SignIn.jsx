import { useState } from 'react'
import { supabase } from './supabase'

export default function SignIn({ onSignedIn, onBack, mode = 'signin', onSwitchMode }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const isSignUp = mode === 'signup'

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')
    setLoading(true)
    const { error: authError } = isSignUp
      ? await supabase.auth.signUp({ email, password })
      : await supabase.auth.signInWithPassword({ email, password })
    setLoading(false)
    if (authError) {
      setError(authError.message)
      return
    }
    // On success the auth state listener in App picks up the new session and
    // renders the app; nothing else to do here.
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
      <h1>{isSignUp ? 'Create your account' : 'Sign in'}</h1>
      <p className="lede">{isSignUp ? 'Sign up to get started.' : 'Sign in to continue.'}</p>
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
            autoComplete={isSignUp ? 'new-password' : 'current-password'}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>
        {error ? <p className="banner banner-error" role="alert">{error}</p> : null}
        <button className="btn primary" type="submit" disabled={loading}>
          {loading
            ? isSignUp ? 'Creating account…' : 'Signing in…'
            : isSignUp ? 'Sign up' : 'Sign in'}
        </button>
      </form>
      {onSwitchMode ? (
        <p className="lede">
          {isSignUp ? 'Already have an account? ' : 'Need an account? '}
          <button
            className="text-btn"
            type="button"
            onClick={() => onSwitchMode(isSignUp ? 'signin' : 'signup')}
          >
            {isSignUp ? 'Log in' : 'Sign up'}
          </button>
        </p>
      ) : null}
    </main>
  )
}

import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { supabase } from './supabase'
import SignIn from './SignIn'
import Landing from './pages/Landing'
import Shell from './components/Shell'
import Overview from './pages/Overview'
import Transactions from './pages/Transactions'
import People from './pages/People'
import Suggestions from './pages/Suggestions'
import Connect from './pages/Connect'
import IMessage from './pages/IMessage'
import './App.css'

export default function App() {
  const [session, setSession] = useState(undefined)
  // null = Landing; 'signin' / 'signup' = which auth form to show.
  const [authMode, setAuthMode] = useState(null)

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session)
    })
    const { data: subscription } = supabase.auth.onAuthStateChange((_event, next) => {
      setSession(next)
    })
    return () => subscription.subscription.unsubscribe()
  }, [])

  if (session === undefined) {
    return (
      <main className="signin">
        <p>Loading…</p>
      </main>
    )
  }

  if (!session) {
    if (!authMode) {
      return (
        <Landing
          onSignIn={() => setAuthMode('signin')}
          onSignUp={() => setAuthMode('signup')}
        />
      )
    }
    return (
      <SignIn
        mode={authMode}
        onSignedIn={() => {}}
        onBack={() => setAuthMode(null)}
        onSwitchMode={setAuthMode}
      />
    )
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Shell />}>
          <Route path="/" element={<Overview />} />
          <Route path="/transactions" element={<Transactions />} />
          <Route path="/people" element={<People />} />
          <Route path="/suggestions" element={<Suggestions />} />
          <Route path="/connect" element={<Connect />} />
          <Route path="/imessage" element={<IMessage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

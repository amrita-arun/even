import { useEffect, useState } from 'react'
import { NavLink, Outlet, Link, useNavigate } from 'react-router-dom'
import { apiFetch } from '../api'
import { initialsFromEmail, nameFromEmail, relativeUpdated } from '../format'
import { supabase } from '../supabase'
import Icon from './Icon'

const NAV = [
  { to: '/', label: 'Overview', icon: 'panels', end: true },
  { to: '/transactions', label: 'Transactions', icon: 'arrow-left-right' },
  { to: '/people', label: 'People', icon: 'users' },
  { to: '/suggestions', label: 'Suggestions', icon: 'layers' },
]

export default function Shell() {
  const navigate = useNavigate()
  const [me, setMe] = useState(null)
  const [pendingCount, setPendingCount] = useState(0)
  const [syncLabel, setSyncLabel] = useState('No card linked')
  const [menuOpen, setMenuOpen] = useState(false)

  async function refreshShell() {
    const [profile, proposals, items] = await Promise.all([
      apiFetch('/api/auth/me'),
      apiFetch('/api/proposals?status=pending'),
      apiFetch('/api/plaid/items'),
    ])
    setMe(profile)
    setPendingCount(proposals.length)
    const latest = [...items].sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)))[0]
    if (!latest) {
      setSyncLabel('No card linked')
    } else {
      const name = latest.institutionName || 'Card'
      setSyncLabel(`${name} · ${relativeUpdated(latest.updatedAt)}`)
    }
  }

  useEffect(() => {
    refreshShell().catch(() => {})
  }, [])

  async function signOut() {
    await supabase.auth.signOut()
    window.location.assign('/')
  }

  const initials = initialsFromEmail(me?.email)
  const greetingName = nameFromEmail(me?.email)

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link to="/" className="wordmark">
          <span className="balance-mark" aria-hidden="true">
            <span />
            <span />
          </span>
          even
        </Link>
        <nav className="desktop-nav" aria-label="Primary">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}
            >
              <Icon name={item.icon} size={17} />
              {item.label}
              {item.to === '/suggestions' && pendingCount > 0 ? (
                <span className="nav-count">{pendingCount}</span>
              ) : null}
            </NavLink>
          ))}
        </nav>
        <div className="account-cluster">
          <span className="bell" aria-hidden="true">
            <Icon name="bell" size={20} />
          </span>
          <button
            type="button"
            className="avatar-btn"
            aria-expanded={menuOpen}
            aria-haspopup="menu"
            onClick={() => setMenuOpen((open) => !open)}
          >
            <span className="avatar tone-forest avatar-sm">{initials}</span>
          </button>
          {menuOpen ? (
            <div className="account-menu" role="menu">
              <p className="menu-email">{me?.email}</p>
              <button type="button" role="menuitem" onClick={() => { setMenuOpen(false); navigate('/connect') }}>
                Connect account
              </button>
              <button type="button" role="menuitem" onClick={signOut}>
                Sign out
              </button>
            </div>
          ) : null}
        </div>
      </header>
      <main className="workspace">
        <Outlet context={{ me, greetingName, initials, pendingCount, refreshShell, syncLabel }} />
      </main>
      <footer className="status-bar">
        <Link to="/connect" className="sync-status">
          <span className="status-dot" />
          {syncLabel}
        </Link>
      </footer>
      <nav className="mobile-nav" aria-label="Primary">
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) => (isActive ? 'mobile-item active' : 'mobile-item')}
          >
            <Icon name={item.icon} size={20} />
            {item.label}
            {item.to === '/suggestions' && pendingCount > 0 ? (
              <span className="nav-count">{pendingCount}</span>
            ) : null}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}

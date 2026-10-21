import { useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { apiFetch } from '../api'
import { allocateCents, avatarTone, errorText, firstName, formatMoney } from '../format'
import Icon from '../components/Icon'
import RuleSheet from '../components/RuleSheet'
import { Avatar, Banner, Modal, PageIntro } from '../components/ui'

const TABS = [
  { id: 'pending', label: 'For review' },
  { id: 'approved', label: 'Approved' },
  { id: 'rejected', label: 'Dismissed' },
]

export default function Suggestions() {
  const { refreshShell, pendingCount } = useOutletContext()
  const [tab, setTab] = useState('pending')
  const [rows, setRows] = useState([])
  const [owed, setOwed] = useState(0)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(null)
  const [people, setPeople] = useState([])
  const [picked, setPicked] = useState([])
  const [ruleFor, setRuleFor] = useState(null)

  async function load(status = tab) {
    const [proposals, balances] = await Promise.all([
      apiFetch(`/api/proposals?status=${status}`),
      apiFetch('/api/reports/balances'),
    ])
    setRows(proposals)
    setOwed(balances.owedToYouCents)
  }

  useEffect(() => {
    load(tab).catch((err) => setError(errorText(err)))
  }, [tab])

  async function act(id, action) {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiFetch(`/api/proposals/${id}/${action}`, { method: 'POST' })
      setNotice(action === 'approve' ? 'Approved. That split is now in the ledger.' : 'Dismissed.')
      await load(tab)
      refreshShell?.()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function startEdit(proposal) {
    setError('')
    try {
      const list = await apiFetch('/api/people')
      setPeople(list.filter((person) => person.kind === 'friend'))
      setPicked(proposal.personIds || [])
      setEditing(proposal)
    } catch (err) {
      setError(errorText(err))
    }
  }

  async function saveEdit(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await apiFetch(`/api/proposals/${editing.id}/edit`, {
        method: 'POST',
        body: JSON.stringify({ personIds: picked }),
      })
      setNotice('Updated. It stays in For review until you approve.')
      setEditing(null)
      await load('pending')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const [lead, ...rest] = rows

  return (
    <>
      <PageIntro title="Suggestions">
        <button type="button" className="btn secondary">
          <Icon name="sliders" size={16} /> Preferences
        </button>
      </PageIntro>
      <div className="chip-row">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={tab === item.id ? 'chip active' : 'chip'}
            onClick={() => setTab(item.id)}
          >
            {item.id === 'pending'
              ? `${item.label} · ${tab === 'pending' ? rows.length : pendingCount}`
              : item.label}
          </button>
        ))}
      </div>
      <Banner>{error}</Banner>
      <Banner tone="ok">{notice}</Banner>
      {rows.length === 0 ? (
        <p className="muted-line">Nothing in {TABS.find((item) => item.id === tab)?.label}.</p>
      ) : null}
      <div className={lead ? 'suggestion-stage' : 'suggestion-stage suggestion-stage-solo'}>
        {lead ? (
          <article className="panel proposal-lead">
            <div className="proposal-charge">
              <Avatar name={lead.description} tone="forest" size={44} />
              <span className="merchant">
                <strong>{lead.description}</strong>
              </span>
              <strong className="mono proposal-amount">{formatMoney(lead.amountCents)}</strong>
            </div>
            <div className="proposal-copy">
              <strong className="proposal-title">{splitTitle(lead.personNames)}</strong>
            </div>
            <ShareBox proposal={lead} />
            <p className="match">
              <Icon name="sparkles" size={14} /> {lead.score}% match
            </p>
            {tab === 'pending' ? (
              <div className="proposal-actions">
                <button type="button" className="btn primary" disabled={busy} onClick={() => act(lead.id, 'approve')}>
                  Approve
                </button>
                <button type="button" className="btn secondary" disabled={busy} onClick={() => startEdit(lead)}>
                  Edit
                </button>
                <button type="button" className="btn secondary" disabled={busy} onClick={() => act(lead.id, 'reject')}>
                  Dismiss
                </button>
              </div>
            ) : null}
        </article>
        ) : null}
        <div className="suggestion-side">
          {tab === 'pending' && lead ? (
            <article className="rule-card">
              <Icon name="equal" size={28} />
              <h2>Recurring split</h2>
              <p>{ruleLede(lead)}</p>
              <div className="rule-card-line" />
              <div className="rule-card-foot">
                <button type="button" className="btn primary" onClick={() => setRuleFor(lead)}>
                  Set up rule
                </button>
                <p>Requires your approval.</p>
              </div>
            </article>
          ) : null}
          <article className="owed-aside">
            <div>
              <p>Friends owe you</p>
              <strong className="mono">{formatMoney(owed)}</strong>
            </div>
            <div className="owed-copy">
              <p>Excludes suggestions.</p>
              <Link to="/people">View people →</Link>
            </div>
          </article>
        </div>
      </div>
      <ul className="proposal-list">
        {rest.map((proposal, index) => (
          <li key={proposal.id} className="panel proposal-compact">
            <Avatar name={proposal.description} tone={avatarTone(index + 1)} size={44} />
            <span className="proposal-merchant">
              <strong>{proposal.description} · {formatMoney(proposal.amountCents)}</strong>
            </span>
            <span className="proposal-copy">
              <strong>{splitTitle(proposal.personNames)}</strong>
              <p>{shareLine(proposal)}</p>
            </span>
            <p className="match">{proposal.score}% match</p>
            {tab === 'pending' ? (
              <span className="proposal-actions">
                <button type="button" className="btn primary" disabled={busy} onClick={() => act(proposal.id, 'approve')}>Approve</button>
                <button type="button" className="btn secondary" disabled={busy} onClick={() => startEdit(proposal)}>Edit</button>
                <button type="button" className="btn secondary" disabled={busy} onClick={() => act(proposal.id, 'reject')}>Dismiss</button>
              </span>
            ) : null}
          </li>
        ))}
      </ul>
      <p className="missing-expense">
        <Link to="/transactions">
          <Icon name="search" size={14} /> Find a missed split
        </Link>
      </p>
      {editing ? (
        <Modal title="Edit suggestion" onClose={() => setEditing(null)} sheet>
          <form className="stack-form" onSubmit={saveEdit}>
            <div className="sheet-head">
              <div>
                <h2>Edit {editing.description}</h2>
                <p className="muted-line">This stays pending. A proposal that already has shares has to be dismissed and described again.</p>
              </div>
              <button type="button" className="icon-btn" aria-label="Close" onClick={() => setEditing(null)}>
                <Icon name="close" size={18} />
              </button>
            </div>
            {people.map((person) => (
              <label key={person.id} className="check-row">
                <input
                  type="checkbox"
                  checked={picked.includes(person.id)}
                  onChange={() => setPicked((current) => (
                    current.includes(person.id)
                      ? current.filter((id) => id !== person.id)
                      : [...current, person.id]
                  ))}
                />
                {person.displayName}
              </label>
            ))}
            <Banner>{error}</Banner>
            <button className="btn primary wide" type="submit" disabled={busy || picked.length === 0}>Save</button>
          </form>
        </Modal>
      ) : null}
      {ruleFor ? (
        <RuleSheet
          merchant={ruleFor.description}
          onClose={() => setRuleFor(null)}
          onSaved={() => setNotice('Rule saved. The next match becomes a suggestion you still approve.')}
        />
      ) : null}
    </>
  )
}

function splitTitle(names) {
  const firsts = (names || []).map(firstName).filter(Boolean)
  if (firsts.length === 0) return 'Split this?'
  if (firsts.length === 1) return `Split with ${firsts[0]}?`
  if (firsts.length === 2) return `Split with ${firsts[0]} and ${firsts[1]}?`
  return `Split with ${firsts.slice(0, -1).join(', ')} & ${firsts.at(-1)}?`
}

function ruleLede(proposal) {
  const names = (proposal.personNames || []).map(firstName).filter(Boolean)
  const who = names.length ? names.join(', ') : 'friends'
  const split = names.length === 1 ? '50/50' : 'Equal'
  return `${proposal.description} · ${who} · ${split}`
}

function equalShares(proposal) {
  const names = (proposal.personNames || []).map(firstName).filter(Boolean)
  const labels = ['You', ...names]
  const amount = proposal.amountCents || 0
  if (labels.length === 0 || amount <= 0) return []
  const cents = allocateCents(amount, labels.map(() => 1))
  return labels.map((label, index) => ({
    label,
    cents: cents[index],
    percent: Math.round((cents[index] / amount) * 100),
  }))
}

function shareLine(proposal) {
  const shares = equalShares(proposal)
  if (shares.length === 0) return ''
  if (shares.length > 3) {
    return `${shares.length} equal shares · ${formatMoney(shares[0].cents)} each`
  }
  return shares.map((share) => `${share.label} ${formatMoney(share.cents)}`).join(' · ')
}

function ShareBox({ proposal }) {
  const shares = equalShares(proposal)
  if (shares.length === 0) return null
  return (
    <div className="share-box">
      {shares.map((share, index) => (
        <p key={`${share.label}-${index}`}>
          <span>{share.label} · {share.percent}%</span>
          <strong className="mono">{formatMoney(share.cents)}</strong>
        </p>
      ))}
    </div>
  )
}

import { useEffect, useMemo, useState } from 'react'
import { apiFetch } from '../api'
import {
  allocateCents,
  avatarTone,
  dollarsToCents,
  errorText,
  firstName,
  formatDay,
  formatMoney,
  moneyParts,
} from '../format'
import Icon from './Icon'
import { Avatar, Banner, Modal } from './ui'

const METHODS = [
  { id: 'equal', label: 'Equal' },
  { id: 'percentage', label: 'Percent' },
  { id: 'itemized', label: 'Itemized' },
]

export default function SplitSheet({ transaction, onClose, onSaved }) {
  const [people, setPeople] = useState([])
  const [selected, setSelected] = useState([])
  const [method, setMethod] = useState('equal')
  const [percents, setPercents] = useState({})
  const [itemInputs, setItemInputs] = useState({})
  const [taxInput, setTaxInput] = useState('0.00')
  const [tipInput, setTipInput] = useState('0.00')
  const [adding, setAdding] = useState(false)
  const [first, setFirst] = useState('')
  const [last, setLast] = useState('')
  const [venmo, setVenmo] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(transaction.splitState === 'split')

  useEffect(() => {
    apiFetch('/api/people')
      .then((rows) => setPeople(rows.filter((person) => person.kind === 'friend')))
      .catch((err) => setError(errorText(err)))
  }, [])

  const friends = people.filter((person) => selected.includes(person.id))
  const parties = useMemo(() => [{ id: null, name: 'You' }, ...friends.map((person) => ({
    id: person.id,
    name: person.displayName,
    venmoHandle: person.venmoHandle,
  }))], [friends])

  const taxCents = dollarsToCents(taxInput)
  const tipCents = dollarsToCents(tipInput)
  const total = transaction.amountCents
  const pretax = taxCents != null && tipCents != null ? total - taxCents - tipCents : null

  const preview = useMemo(() => {
    if (pretax == null || pretax < 0 || parties.length === 0) return null
    let weights
    let pretaxShares
    if (method === 'equal') {
      weights = parties.map(() => 1)
      pretaxShares = allocateCents(pretax, weights)
    } else if (method === 'percentage') {
      const points = parties.map((party) => {
        const raw = Number(percents[party.id ?? 'you'] ?? '')
        if (!Number.isInteger(raw) || raw < 0) return null
        return raw * 100
      })
      if (points.some((point) => point == null)) return null
      if (points.reduce((sum, point) => sum + point, 0) !== 10000) return null
      weights = points
      pretaxShares = allocateCents(pretax, weights)
    } else {
      pretaxShares = parties.map((party) => dollarsToCents(itemInputs[party.id ?? 'you'] ?? ''))
      if (pretaxShares.some((cents) => cents == null || cents < 0)) return null
      if (pretaxShares.reduce((sum, cents) => sum + cents, 0) !== pretax) return null
      weights = pretaxShares.map((cents) => cents || 0)
    }
    const taxShares = allocateCents(taxCents || 0, weights.map((weight) => weight || 0))
    const tipShares = allocateCents(tipCents || 0, weights.map((weight) => weight || 0))
    return parties.map((party, index) => ({
      ...party,
      pretaxCents: pretaxShares[index],
      shareCents: pretaxShares[index] + taxShares[index] + tipShares[index],
      percentLabel: method === 'itemized'
        ? null
        : `${Math.round((weights[index] / weights.reduce((sum, weight) => sum + weight, 0)) * 100)}%`,
    }))
  }, [itemInputs, method, parties, percents, pretax, taxCents, tipCents])

  function chooseMethod(next) {
    setMethod(next)
    if (next !== 'percentage') return
    const count = parties.length
    const base = Math.floor(100 / count)
    const extra = 100 - base * count
    const seeded = {}
    parties.forEach((party, index) => {
      seeded[party.id ?? 'you'] = String(base + (index < extra ? 1 : 0))
    })
    setPercents(seeded)
  }

  const yourShare = preview?.find((party) => party.id == null)?.shareCents ?? null
  const toRequest = preview
    ? preview.filter((party) => party.id != null).reduce((sum, party) => sum + party.shareCents, 0)
    : null

  function togglePerson(id) {
    setSelected((current) => (
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id]
    ))
  }

  async function addPerson(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const created = await apiFetch('/api/people', {
        method: 'POST',
        body: JSON.stringify({
          firstName: first.trim(),
          lastName: last.trim(),
          venmoHandle: venmo.trim() || null,
          kind: 'friend',
        }),
      })
      setPeople((current) => [...current, created])
      setSelected((current) => [...current, created.id])
      setFirst('')
      setLast('')
      setVenmo('')
      setAdding(false)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  function sharePayload(party) {
    if (method === 'percentage') {
      return {
        personId: party.id,
        shareCents: null,
        basisPoints: Number(percents[party.id ?? 'you']) * 100,
        itemizedCents: null,
      }
    }
    if (method === 'itemized') {
      return {
        personId: party.id,
        shareCents: null,
        basisPoints: null,
        itemizedCents: dollarsToCents(itemInputs[party.id ?? 'you']),
      }
    }
    return { personId: party.id, shareCents: null, basisPoints: null, itemizedCents: null }
  }

  async function requestSplit() {
    if (!preview || taxCents == null || tipCents == null) {
      setError('Enter tax and tip in dollars, and make the shares add up.')
      return
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiFetch(`/api/transactions/${transaction.id}/split`, {
        method: 'POST',
        body: JSON.stringify({
          method,
          taxCents,
          tipCents,
          shares: parties.map(sharePayload),
        }),
      })
      setSaved(true)
      setNotice('Split saved. Requesting here does not send a message.')
      onSaved?.()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function openVenmo(personId) {
    const person = people.find((item) => item.id === personId)
    if (!person?.venmoHandle) {
      setError('Save a Venmo username for this person before opening Venmo.')
      return
    }
    setBusy(true)
    setError('')
    try {
      const draft = await apiFetch(`/api/settlements/draft?personId=${personId}&channel=venmo`)
      if (draft.url) window.open(draft.url, '_blank', 'noopener,noreferrer')
      setNotice(draft.text || 'Venmo draft ready.')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function copyZelle(personId) {
    setBusy(true)
    setError('')
    try {
      const draft = await apiFetch(`/api/settlements/draft?personId=${personId}&channel=zelle`)
      await navigator.clipboard.writeText(draft.text)
      setNotice('Copied the Zelle draft. Nothing was sent.')
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const hero = moneyParts(total)
  const subtotal = pretax != null && pretax >= 0 ? pretax : null

  return (
    <Modal title="Split expense" onClose={onClose} sheet>
      <div className="sheet-head">
        <div>
          <p className="eyebrow">Split expense</p>
          <h2>{transaction.description}</h2>
          <p className="muted-line">
            {formatLedgerStamp(transaction)}
          </p>
        </div>
        <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}>
          <Icon name="close" size={18} />
        </button>
      </div>
      <div className="bill-total">
        <p className="hero-amount">
          <span>{hero.dollars}</span>
          <span className="cents">.{hero.cents}</span>
        </p>
        <p className="muted-line">Card charge</p>
      </div>
      <div className="chip-row" role="tablist" aria-label="Split method">
        {METHODS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={method === item.id ? 'chip active' : 'chip'}
            onClick={() => chooseMethod(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div className="sheet-section-head">
        <p>{parties.length} {parties.length === 1 ? 'person' : 'people'}</p>
        <button type="button" className="text-btn" onClick={() => setAdding((open) => !open)}>
          <Icon name="plus" size={14} /> Add people
        </button>
      </div>
      {adding ? (
        <form className="inline-form" onSubmit={addPerson}>
          <input value={first} onChange={(e) => setFirst(e.target.value)} placeholder="First" required aria-label="First name" />
          <input value={last} onChange={(e) => setLast(e.target.value)} placeholder="Last" required aria-label="Last name" />
          <input value={venmo} onChange={(e) => setVenmo(e.target.value)} placeholder="Venmo username" aria-label="Venmo username" />
          <button className="btn primary" type="submit" disabled={busy}>Add</button>
        </form>
      ) : null}
      <ul className="pick-list">
        {people.map((person, index) => (
          <li key={person.id}>
            <label>
              <input
                type="checkbox"
                checked={selected.includes(person.id)}
                onChange={() => togglePerson(person.id)}
              />
              <Avatar name={person.displayName} tone={avatarTone(index)} size={32} />
              {person.displayName}
              {person.venmoHandle ? <span className="muted-line">@{person.venmoHandle}</span> : null}
            </label>
          </li>
        ))}
      </ul>
      <ul className="share-list">
        {parties.map((party, index) => {
          const row = preview?.find((item) => item.id === party.id)
          const key = party.id ?? 'you'
          return (
            <li key={key}>
              <Avatar name={party.name} tone={party.id == null ? 'forest' : avatarTone(index)} size={36} />
              <div>
                <p>{party.id == null ? 'You' : firstName(party.name)}</p>
                {party.venmoHandle ? <p className="muted-line">@{party.venmoHandle}</p> : null}
              </div>
              {method === 'percentage' ? (
                <label className="share-input">
                  <input
                    inputMode="numeric"
                    aria-label={`${party.name} percent`}
                    value={percents[key] ?? ''}
                    onChange={(event) => setPercents((current) => ({ ...current, [key]: event.target.value }))}
                  />
                  %
                </label>
              ) : null}
              {method === 'itemized' ? (
                <label className="share-input">
                  $
                  <input
                    inputMode="decimal"
                    aria-label={`${party.name} items`}
                    value={itemInputs[key] ?? ''}
                    onChange={(event) => setItemInputs((current) => ({ ...current, [key]: event.target.value }))}
                  />
                </label>
              ) : null}
              {method === 'equal' && row?.percentLabel ? <span className="muted-line">{row.percentLabel}</span> : null}
              <strong>{row ? formatMoney(row.shareCents) : '—'}</strong>
            </li>
          )
        })}
      </ul>
      <div className="bill-lines">
        <p><span>Subtotal</span><span>{subtotal == null ? '—' : formatMoney(subtotal)}</span></p>
        <label>
          Tax
          <input value={taxInput} onChange={(event) => setTaxInput(event.target.value)} inputMode="decimal" aria-label="Tax in dollars" />
        </label>
        <label>
          Tip
          <input value={tipInput} onChange={(event) => setTipInput(event.target.value)} inputMode="decimal" aria-label="Tip in dollars" />
        </label>
      </div>
      <div className="share-summary">
        <p><span>Your share</span><strong>{yourShare == null ? '—' : formatMoney(yourShare)}</strong></p>
        <p><span>To request</span><strong>{toRequest == null ? '—' : formatMoney(toRequest)}</strong></p>
      </div>
      <Banner>{error}</Banner>
      <Banner tone="ok">{notice}</Banner>
      <button className="btn primary wide" type="button" disabled={busy || !preview || friends.length === 0} onClick={requestSplit}>
        {toRequest == null ? 'Request' : `Request ${formatMoney(toRequest)}`}
      </button>
      <div className="pay-row">
        {friends.map((person) => (
          <span key={person.id} className="pay-person">
            <button type="button" className="btn secondary" disabled={busy || !saved} onClick={() => openVenmo(person.id)}>
              <Icon name="arrow-up-right" size={14} /> Venmo
            </button>
            <button type="button" className="btn secondary" disabled={busy || !saved} onClick={() => copyZelle(person.id)}>
              Zelle
            </button>
          </span>
        ))}
      </div>
      <p className="fine-print">
        Draft · No requests sent
      </p>
    </Modal>
  )
}

function formatLedgerStamp(transaction) {
  const day = formatDay(transaction.postedOn)
  const kind = transaction.category || transaction.kind || ''
  return [day, kind].filter(Boolean).join(' · ')
}

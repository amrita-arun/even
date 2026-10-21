import { useEffect, useState } from 'react'
import { apiFetch } from '../api'
import { avatarTone, errorText, firstName } from '../format'
import Icon from './Icon'
import { Avatar, Banner, Modal } from './ui'

export default function RuleSheet({ merchant = '', onClose, onSaved }) {
  const [people, setPeople] = useState([])
  const [groups, setGroups] = useState([])
  const [target, setTarget] = useState('person')
  const [personId, setPersonId] = useState('')
  const [groupId, setGroupId] = useState('')
  const [groupName, setGroupName] = useState('')
  const [groupPeople, setGroupPeople] = useState([])
  const [creatingGroup, setCreatingGroup] = useState(false)
  const [method, setMethod] = useState('equal')
  const [friendPercent, setFriendPercent] = useState('50')
  const [merchantMatch, setMerchantMatch] = useState(merchant)
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    Promise.all([apiFetch('/api/people'), apiFetch('/api/groups')])
      .then(([personRows, groupRows]) => {
        setPeople(personRows.filter((person) => person.kind === 'friend'))
        setGroups(groupRows)
      })
      .catch((err) => setError(errorText(err)))
  }, [])

  function toggleGroupPerson(id) {
    setGroupPeople((current) => (
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id]
    ))
  }

  async function saveRule(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      let nextGroupId = target === 'group' ? groupId : null
      if (target === 'group' && creatingGroup) {
        if (!groupName.trim() || groupPeople.length === 0) {
          throw new Error('Name the group and choose at least one friend.')
        }
        const created = await apiFetch('/api/groups', {
          method: 'POST',
          body: JSON.stringify({ name: groupName.trim(), personIds: groupPeople }),
        })
        nextGroupId = created.id
        setGroups((current) => [...current, created])
      }
      if (target === 'person' && !personId) throw new Error('Choose a person.')
      if (target === 'group' && !nextGroupId) throw new Error('Choose a saved group.')
      let basisPoints = null
      if (method === 'percentage') {
        const percent = Number(friendPercent)
        if (!Number.isInteger(percent) || percent < 1 || percent > 99) {
          throw new Error('Friends’ share is a whole percent from 1 to 99. You keep the rest.')
        }
        basisPoints = percent * 100
      }
      const label = name.trim() || `${merchantMatch.trim()} split`
      await apiFetch('/api/split-rules', {
        method: 'POST',
        body: JSON.stringify({
          name: label,
          merchantMatch: merchantMatch.trim(),
          method,
          personId: target === 'person' ? personId : null,
          groupId: target === 'group' ? nextGroupId : null,
          basisPoints,
        }),
      })
      onSaved?.()
      onClose()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const selectedPerson = people.find((person) => person.id === personId)

  return (
    <Modal title="Set up rule" onClose={onClose} sheet>
      <form onSubmit={saveRule}>
        <div className="sheet-head">
          <div>
            <h2>Set up rule</h2>
          </div>
          <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}>
            <Icon name="close" size={18} />
          </button>
        </div>
        <label className="stack-field">
          Merchant
          <input value={merchantMatch} onChange={(event) => setMerchantMatch(event.target.value)} required />
        </label>
        <p className="sheet-section-head">Split with</p>
        <div className="chip-row">
          <button type="button" className={target === 'person' ? 'chip active' : 'chip'} onClick={() => setTarget('person')}>
            Person
          </button>
          <button type="button" className={target === 'group' ? 'chip active' : 'chip'} onClick={() => setTarget('group')}>
            Saved group
          </button>
        </div>
        {target === 'person' ? (
          <ul className="choice-list">
            {people.map((person, index) => (
              <li key={person.id}>
                <button
                  type="button"
                  className={personId === person.id ? 'choice selected' : 'choice'}
                  onClick={() => setPersonId(person.id)}
                >
                  <Avatar name={person.displayName} tone={avatarTone(index)} size={36} />
                  <span>{firstName(person.displayName)}</span>
                  {personId === person.id ? <em>Selected</em> : null}
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <>
            <ul className="choice-list">
              {groups.map((group) => (
                <li key={group.id}>
                  <button
                    type="button"
                    className={groupId === group.id && !creatingGroup ? 'choice selected' : 'choice'}
                    onClick={() => { setCreatingGroup(false); setGroupId(group.id) }}
                  >
                    <span className="avatar tone-moss">{initials(group.name)}</span>
                    <span>
                      {group.name}
                      <small>{group.personIds.length} people</small>
                    </span>
                    {groupId === group.id && !creatingGroup ? <em>Selected</em> : null}
                  </button>
                </li>
              ))}
            </ul>
            <button type="button" className="text-btn" onClick={() => setCreatingGroup(true)}>
              <Icon name="plus" size={14} /> Create a group
            </button>
            {creatingGroup ? (
              <div className="group-create">
                <input value={groupName} onChange={(event) => setGroupName(event.target.value)} placeholder="Group name" aria-label="Group name" />
                {people.map((person) => (
                  <label key={person.id}>
                    <input
                      type="checkbox"
                      checked={groupPeople.includes(person.id)}
                      onChange={() => toggleGroupPerson(person.id)}
                    />
                    {person.displayName}
                  </label>
                ))}
              </div>
            ) : null}
          </>
        )}
        <p className="sheet-section-head">Split method</p>
        <div className="chip-row">
          <button type="button" className={method === 'equal' ? 'chip active' : 'chip'} onClick={() => setMethod('equal')}>
            Equal
          </button>
          <button type="button" className={method === 'percentage' ? 'chip active' : 'chip'} onClick={() => setMethod('percentage')}>
            Percent
          </button>
        </div>
        {method === 'percentage' ? (
          <label className="stack-field">
            Friends’ share, percent
            <input
              inputMode="numeric"
              value={friendPercent}
              onChange={(event) => setFriendPercent(event.target.value)}
            />
          </label>
        ) : null}
        <div className="split-preview">
          <p className="eyebrow">Shares</p>
          <p>
            {target === 'person' && selectedPerson
              ? `You + ${firstName(selectedPerson.displayName)}`
              : 'You + the group'}
            {method === 'equal' ? ' · Equal shares' : ` · Friends ${friendPercent || '—'}%`}
          </p>
        </div>
        <label className="stack-field">
          Name (optional)
          <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Waymo with Jo" />
        </label>
        <p className="reassure">
          <Icon name="shield" size={16} />
          <span>Requests always need your approval.</span>
        </p>
        <Banner>{error}</Banner>
        <button className="btn primary wide" type="submit" disabled={busy || !merchantMatch.trim()}>
          Save rule
        </button>
      </form>
    </Modal>
  )
}

function initials(name) {
  return String(name || '')
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0] || '')
    .join('')
    .toUpperCase()
}

import { currentMonth, initialsFromName, shiftMonth } from '../format'
import Icon from './Icon'

export function Avatar({ name, initials, tone = 'forest', size = 40 }) {
  return (
    <span className={`avatar tone-${tone}`} style={{ width: size, height: size }}>
      {initials || initialsFromName(name)}
    </span>
  )
}

export function PageIntro({ eyebrow, title, lede, children }) {
  return (
    <div className="page-intro">
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1>{title}</h1>
        {lede ? <p className="lede">{lede}</p> : null}
      </div>
      {children}
    </div>
  )
}

export function Banner({ tone = 'error', children }) {
  if (!children) return null
  return (
    <p className={`banner banner-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      {children}
    </p>
  )
}

export function MonthButton({ month, label, onChange }) {
  const atCurrent = month >= currentMonth()
  return (
    <div className="month-switch">
      <button
        type="button"
        className="icon-btn"
        aria-label="Previous month"
        onClick={() => onChange(shiftMonth(month, -1))}
      >
        <Icon name="arrow-left" size={16} />
      </button>
      <label className="month-btn">
        <span className="month-btn-face">
          <Icon name="calendar" size={16} />
          {label}
        </span>
        <input
          type="month"
          aria-label="Month"
          max={currentMonth()}
          value={month}
          onChange={(event) => {
            if (event.target.value) onChange(event.target.value)
          }}
        />
      </label>
      <button
        type="button"
        className="icon-btn"
        aria-label="Next month"
        disabled={atCurrent}
        onClick={() => onChange(shiftMonth(month, 1))}
      >
        <Icon name="arrow-right" size={16} />
      </button>
    </div>
  )
}

export function Modal({ title, onClose, children, sheet = false }) {
  return (
    <div className="modal-root" role="presentation">
      <button className="modal-backdrop" type="button" aria-label="Close" onClick={onClose} />
      <div
        className={sheet ? 'modal-sheet' : 'modal-card'}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        {children}
      </div>
    </div>
  )
}

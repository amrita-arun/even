import { initialsFromName } from '../format'
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
  return (
    <label className="month-btn">
      <span className="month-btn-face">
        <Icon name="calendar" size={16} />
        {label}
      </span>
      <input
        type="month"
        aria-label="Month"
        value={month}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
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

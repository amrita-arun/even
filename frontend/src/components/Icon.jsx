import arrowLeft from '../assets/icons/arrow-left.svg?raw'
import arrowLeftRight from '../assets/icons/arrow-left-right.svg?raw'
import arrowRight from '../assets/icons/arrow-right.svg?raw'
import arrowUp from '../assets/icons/arrow-up.svg?raw'
import arrowUpRight from '../assets/icons/arrow-up-right.svg?raw'
import bell from '../assets/icons/bell.svg?raw'
import calendar from '../assets/icons/calendar-days.svg?raw'
import check from '../assets/icons/circle-check.svg?raw'
import card from '../assets/icons/credit-card.svg?raw'
import chevron from '../assets/icons/chevron-right.svg?raw'
import download from '../assets/icons/download.svg?raw'
import equal from '../assets/icons/equal.svg?raw'
import info from '../assets/icons/info.svg?raw'
import landmark from '../assets/icons/landmark.svg?raw'
import layers from '../assets/icons/layers2.svg?raw'
import lock from '../assets/icons/lock-keyhole.svg?raw'
import panels from '../assets/icons/panels-top-left.svg?raw'
import plus from '../assets/icons/plus.svg?raw'
import search from '../assets/icons/search.svg?raw'
import shield from '../assets/icons/shield-check.svg?raw'
import sliders from '../assets/icons/sliders-horizontal.svg?raw'
import sparkles from '../assets/icons/sparkles.svg?raw'
import unlink from '../assets/icons/unlink.svg?raw'
import users from '../assets/icons/users.svg?raw'
import close from '../assets/icons/x.svg?raw'

const ICONS = {
  'arrow-left': arrowLeft,
  'arrow-left-right': arrowLeftRight,
  'arrow-right': arrowRight,
  'arrow-up': arrowUp,
  'arrow-up-right': arrowUpRight,
  bell,
  calendar,
  check,
  card,
  chevron,
  download,
  equal,
  info,
  landmark,
  layers,
  lock,
  panels,
  plus,
  search,
  shield,
  sliders,
  sparkles,
  unlink,
  users,
  close,
}

function svgMarkup(raw, size) {
  return raw
    .replace(/stroke="[^"]*"/g, 'stroke="currentColor"')
    .replace(/\sclip-path="url\([^)]+\)"/g, '')
    .replace(/<defs>[\s\S]*?<\/defs>/g, '')
    .replace(/preserveAspectRatio="none"/, 'preserveAspectRatio="xMidYMid meet"')
    .replace(/width="[^"]*"/, `width="${size}"`)
    .replace(/height="[^"]*"/, `height="${size}"`)
    .replace(/\sstyle="[^"]*"/, '')
}

export default function Icon({ name, size = 17 }) {
  const raw = ICONS[name]
  if (!raw) return null
  return (
    <span
      className="icon"
      style={{ width: size, height: size }}
      aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: svgMarkup(raw, size) }}
    />
  )
}

# Console Page Overrides

> **PROJECT:** Field Sense
> **Generated:** 2026-10-10 19:03:01
> **Page Type:** Dashboard / Data View

> ⚠️ **IMPORTANT:** Rules in this file **override** the Master file (`design-system/MASTER.md`).
> Only deviations from the Master are documented here. For all other rules, refer to the Master.

---

## Page-Specific Rules

### Layout Overrides

- **Max Width:** 1400px or full-width
- **Grid:** 12-column grid for data flexibility
- **Sections:** Hero (product + live preview or status) > Key metrics/indicators > How it works > CTA (Start trial / Contact)

### Spacing Overrides

- **Content Density:** High — optimize for information display

### Typography Overrides

- **Body:** Fira Sans 400/500/600. **Figures, IDs, timestamps:** Fira Code, tabular.
  The Master's Atkinson Hyperlegible stays on the public side; the console reads as an instrument.
- **Base size 14px**, never below 12px for any text.

### Color Overrides

- **Strategy:** Dark or neutral. Status colors (green/amber/red). Data-dense but scannable.

| Role | Hex | Notes |
|------|-----|-------|
| Background | `#0F172A` | slate-900 |
| Sidebar | `#0B1222` | one step below the background |
| Surface / card | `#1B2336` | |
| Surface raised | `#272F42` | hover rows, inputs |
| Border | `#334155` | slate-700; `#475569` for emphasis |
| Text | `#F8FAFC` | |
| Muted text | `#94A3B8` | 6.3:1 on the surface |
| Primary / OK | `#22C55E` | green-500, dark text `#0F172A` on fills |
| Warning | `#FBBF24` | amber-400 |
| Failed / critical | `#F87171` | red-400 as text; fills at 15% opacity |
| Focus ring | `#4ADE80` | green-400, 2px, always visible |

### Component Overrides

- Avoid: Leave UI frozen with no feedback
- Avoid: Make dragging the only way to reorder resize or select

---

## Page-Specific Components

- No unique components for this page

---

## Recommendations

- Effects: Hover tooltips, chart zoom on click, row highlighting on hover, smooth filter animations, data loading spinners
- Animation: Use skeleton screens or spinners
- Accessibility: Add buttons menus or tap-to-move controls and retain keyboard operation
- CTA Placement: Primary CTA in nav + After metrics

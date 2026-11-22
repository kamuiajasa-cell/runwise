# RunSense — Design Direction

## Visual and motion language

direction:
  id: runsense-navy-green
  name: RunSense editorial performance
  status: observed
  philosophy: A calm, focused running companion; performance information is clear without making every metric compete for attention.
  grammar: Editorial page headers, compact uppercase eyebrows, generous vertical rhythm, rounded information cards, restrained lime emphasis, and a persistent bottom navigation.
  answers: The direction is observed from the user's existing `runsense.html` (library file: runsense (19).html) and the current native Compose app. The explicit redesign request asks the native app to match that HTML reference.
  type:
    families: System sans-serif, Inter-like proportions
    scale: 9sp eyebrow; 11–13sp supporting text; 16–20sp section/card titles; 32–38sp page titles; 26–29sp primary metrics
    figures: Tabular numerals for pace, heart rate, distance, and load
  colour:
    scheme: Dark default
    neutrals:
      background: "#07152D"
      card: "#0D2040"
      elevated: "#12294A"
      text: "#F5F8FF"
      muted: "#9AAAC3"
      border: "rgba(150,185,230,0.13)"
    accent:
      primary: "#58E83B"
      warning: "#FFC36A"
      danger: "#FF5252"
    light:
      background: "#EFF4FB"
      card: "#FFFFFF"
      text: "#0B1A33"
      muted: "#63728A"
      primary: "#299D35"
  shape:
    radius: [10, 16, 20, 26]
    cards: Large rounded hero card; 20dp content cards
  depth: Flat surfaces with subtle borders; avoid heavy elevation.
  spacing: 4dp base rhythm; 12–20dp content gaps; 20–22dp page gutters.
  layout: Mobile-first, single-column scrolling pages; editorial header per page; a floating pill bottom navigation with four tabs (Hari Ini, Riwayat, Progres, Saya) exactly like the HTML. Coach and Jam are child pages of Saya with a back bar; the Saya tab stays highlighted while they are open.
  background rhythm: Dark continuous canvas; card surfaces alternate with open space and thin section dividers.
  material: Matte, low-gloss surfaces.
  imagery: No decorative imagery required; use the real RunSense mark where available.
  motion: Minimal state transitions; no decorative motion.
  signature: Oversized two-line page title with lime second line, compact uppercase eyebrow, and restrained lime actions.
  avoid: Permanent branded top bar, generic repeated card stacks without section hierarchy, excessive badges, gradients, glow, and invented metrics.
  trade-offs: Keep the native app's additional Coach, Watch, Strength, and PAR-Q workflows; preserve data and actions rather than copying the HTML's smaller navigation structure.

## Observed evidence

- `runsense.html` final cascade (last `:root` override): background #07152D, card #0D2040, primary #58E83B, text #F5F8FF, muted #9AAAC3. Earlier lime/graphite blocks in the file are overridden.
- The HTML Home page leads with "Run with your rhythm.", then today's training card, body/leg context, weekly readiness, and recent sync status.
- HTML History and Progress pages use distinct editorial headers, filter docks, summaries, and grouped cards.
- The native app already used the same dark/lime palette, but a permanent top bar and generic page headings weakened the match.
- This redesign keeps native Compose and the existing business logic, data flows, and destinations.

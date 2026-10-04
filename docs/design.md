# How Honest Robin feels

The design philosophy of Honest Robin: Time, and of every Honest Robin product. The app doesn't
meet all of it yet; where it falls short, that's a bug to fix.

## Simple first, powerful when needed

- The common thing takes one step. Tracking time means saying what you're working on and pressing
  Start.
- Everything else is one step away. A missing client, project or task is entered as you go and
  changed later; keyboard shortcuts are behind `?`; rarely used settings live in Settings, not in
  the main menu.
- Power never gets in the way of the common thing, and simplicity never hides what a professional
  needs: the week view, reports, the API and a full export are all there.

## Professional, and playful

- People should enjoy using it: quick, clear and friendly, with a little play in it.
- The robin keeps people company in empty states, on the first run and in live moments such as a
  running timer. It stays out of the way, and out of anything about money, data or errors.
- Words sound like a person: short sentences, plain words, a bit of warmth. Humour, when there is
  some, is never at the user's expense.
- It's never a toy. Numbers line up, money adds up, data is handled with care, and when something
  goes wrong we say so plainly and seriously.

## What isn't there

Much of the joy is in what we leave out. Each of these is a bug:

- **Tricks:** fake urgency, buttons that guilt people, pre-ticked boxes, hidden ways out, nagging
  to upgrade.
- **Noise:** emails or notifications whose job is to pull people back in. Only send what people
  would want, and let them turn off each kind.
- **Corporate language:** jargon and filler. Say what the thing does.
- **AI for its own sake:** an AI feature has to save real work. It's labelled, it can be turned
  off, and it never writes or sends anything in someone's name without them seeing it first.

## Honest

Honesty is the first project rule (`CLAUDE.md`): never mislead, and disclose by default. The
design shows prices, limits and mistakes plainly, with numbers where there are numbers.

## The look

- The palette is in `frontend/src/design/tokens.css`: sage paper, forest-green actions, dark ink.
  The robin's orange (`#F26A2E`) is a sparing accent for live moments, never for text.
- The robin mascots are in `docs/brand/mascot/`.
- Accessible by default: real buttons, links and labels; text contrast of at least 4.5:1; nothing
  told apart by colour alone; everything usable from the keyboard.

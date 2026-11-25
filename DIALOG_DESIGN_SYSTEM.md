# ThunBudget dialog visual system

All modal windows use the shared `AppAlertDialog` surface in `DialogTheme.kt`.
Do not introduce one-off Material or platform AlertDialogs for app workflows.

## Dark theme
- Graphite dialog surface: #262630
- Raised row/input surface: #373741
- Primary text: #F4F2FF
- Secondary text: #B8B4C5
- Violet action accent: #B7A0FF
- Subtle graphite outline

## Light theme
- Preserve the existing light palette and white dialog surface.
- Keep the same geometry as dark mode so switching themes does not change layout.

## Shared geometry
- 92% of available width, maximum 440 dp
- 24 dp corner radius
- 20 dp horizontal inset; 18 dp top and 16 dp bottom inset
- 12 dp vertical rhythm
- 20 sp title, 14 sp body, 14 sp action labels
- Shared component shapes for text fields, menus, cards and buttons

## Coverage
Every screen-level modal should be implemented through `AppAlertDialog`.
Dropdown menus opened from dialogs inherit the dialog-local MaterialTheme.
Keep app navigation, bottom bar, and page layout outside this dialog-only change.

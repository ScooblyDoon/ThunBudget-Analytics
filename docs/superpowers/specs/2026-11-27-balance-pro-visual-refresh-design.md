# Balance Pro visual refresh — design specification

## Intent
Apply the approved Balance Pro presentation to ThunBudget Analytics as a visual-only refresh. The application should feel calmer, more cohesive and easier to read without changing the product users already know.

## Goals
- Centralize a coherent light and dark palette.
- Use a single outlined icon language instead of text glyphs in navigation and quick actions.
- Make cards, buttons, input fields and dialogs visually consistent.
- Improve chart color consistency, scale/grid legibility and chart mark styling.
- Preserve all existing screen content, controls, navigation order, financial calculations and interaction behavior.

## Non-goals and anti-goals
- No new product features or new dashboard blocks.
- No navigation redesign, no reordering tabs, no removal or renaming of existing sections.
- No changes to reporting-period definitions, income/expense calculations, category allocation, carryover, forecasts, filtering, persistence, import/export or backup semantics.
- No cloud services, analytics tracking, network requirements or external transmission of financial data.
- No release publication or automatic merge to main.

## Accepted visual system
### Light theme
- Background: #F4F6F8
- Surface: #FFFFFF
- Card surface: #E6E0E9
- Primary slate: #445365
- Positive / remaining: #168974
- Warning: #DFA44B
- Overspend / error: #D96B76
- Outline: #D9DEE5
- Text: #1F2937; secondary text: #6B7280
- Modal surface: #FFFFFF; input surface: #F0F2F5

### Dark theme
- Background: #090A10
- Surface: #171821
- Secondary surface: #242631
- Muted gray-lavender accent: #89849A
- Main text: #F3F2F8; secondary text: #B4B7C2
- Modal surface remains graphite #21222D; modal inputs remain #343440.
- Positive/negative financial states remain semantic green/red, not the lavender accent.

### Icons and components
- Use one outlined Material icon family for navigation, header actions, quick actions, search and editing affordances.
- Maintain the six existing destinations and their current IDs/order: Overview, Analysis, Charts, Flexible Periods, History, Recurring Payments.
- Keep the existing top actions and quick-action behavior.
- Apply the same shape, spacing, outline and state language to existing cards and dialogs.

## Existing screen content that must remain
- Overview retains exactly the existing four data blocks in their established default order: reporting-period selector/details, budget information, income operations for the selected period, and budget by categories. Existing user-customized order remains available.
- Analysis keeps all current controls, source-of-income choices, forecast, period/month/year breakdowns and recommendations.
- Charts keep the current reporting-period, calendar-month and annual sections and category selection controls.
- Flexible Periods, History, Recurring Payments, Assistant, Settings, backup/restore and Excel flows remain present and behave as today.

## Charts
- Reuse a single semantic category palette across period/month/year views.
- Keep the same data sources, date filters, category selection rules and values.
- Preserve money amounts and percentages in the legend.
- Retain labeled Y-axis units in thousands of rubles and month/date labels.
- Improve grid contrast, bar corner treatment and card hierarchy only; no new calculation or alternate denominator.

## Dialogs
- All dialogs use the shared AppAlertDialog style.
- Dark modal background must remain #21222D and input surfaces #343440.
- A direct platform AlertDialog in the flexible-period editor must be brought into the shared modal system so it respects both themes.
- Preserve the existing text, validation, actions, destructive confirmations and consequence explanations.

## Implementation approach
Use the existing Jetpack Compose Material 3 theme and composables. First centralize palette tokens; then apply those tokens to the shared theme/dialog layer and the existing screens. Do not restructure MainActivity or replace the current screen composition with new architecture.

## Acceptance
- Existing navigation destinations, ordering behavior, overview block IDs/order, values, formulas and event handlers are unchanged.
- New palette unit tests pass.
- Existing dialog screenshot tests remain green and dark modal surface/field colors remain graphite.
- GitHub Actions unit tests, signed Release APK build and signature verification pass.
- UI screenshot evidence is reviewed in both themes when an emulator/device runner is available. Pixel 8 manual check is still required before visual acceptance.

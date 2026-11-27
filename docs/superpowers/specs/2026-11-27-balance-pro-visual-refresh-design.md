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
- Surface: #FFFFFF; neutral card surface: #E6E0E9
- Primary slate: #445365; primary container: #E6E0E9
- Selected controls / filters: #E6E0E9 with slate text #445365
- Budget highlight card: #445365 with white text
- Positive / remaining: #168974
- Warning: #DFA44B
- Overspend / error: #D36F83
- Outline: #DDD5E5
- Text: #241F2A; secondary text: #6D6575
- Modal surface: #FFFFFF; input surface: #F0F2F5

### Dark theme
- Background: #090A11
- Surface: #171821; neutral card surface: #36343C
- Surface containers progress from #171821 to #36343C
- Primary violet: #B49BFE; on-primary ink: #1B1220
- Selected controls / navigation and microphone action: #3C344B; rose income action: #623B48
- Budget highlight card: #B49BFE with dark text #1B1220
- Actual-spend fill: #168A72; track: #383548
- Main text: #F3F2F9; secondary text: #B8B6C0
- Modal surface remains graphite #21222D; modal inputs remain #343440.
- Positive/negative financial states remain semantic green/red, not the lavender accent.

### Icons and components
- Use one outlined Material icon family for navigation, header actions, quick actions, search and editing affordances.
- Maintain the six existing destinations and their current IDs/order: Overview, Analysis, Charts, Flexible Periods, History, Recurring Payments.
- Keep all existing top actions and quick-action behavior; on compact widths show header actions as icon-only controls so the app title and subtitle stay on one line.
- Apply the same shape, spacing, outline and state language to existing cards and dialogs.

## Existing screen content that must remain
- Overview retains exactly the existing four data blocks in their established default order: reporting-period selector/details, budget information, income operations for the selected period, and budget by categories. Existing user-customized order remains available.
- Analysis keeps all current controls, source-of-income choices, forecast, period/month/year breakdowns and recommendations.
- Charts keep the current reporting-period, calendar-month and annual sections and category selection controls.
- Flexible Periods, History, Recurring Payments, Assistant, Settings, backup/restore and Excel flows remain present and behave as today.

## Charts
- Reuse the established distinct multi-series palette for category donut charts (blue, teal, amber, violet, red, cyan, olive and gray).
- Single-series monthly bars use the theme's primary violet accent.
- Spending execution bars use teal #168A72 with a muted plum track #383548; overspend remains semantic red.
- Keep the same data sources, date filters, category selection rules and values.
- Preserve money amounts and percentages in the legend.
- Retain labeled Y-axis units in thousands of rubles and month/date labels.
- Give all card/container levels an explicit theme tint so charts do not fall back to neutral gray surfaces.
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
- Budget-card text and progress fill remain readable in dark mode.
- Mic/voice actions and selected navigation/filter states use muted violet; income quick action uses dusty rose.
- Dark budget card is bright violet #B49BFE with dark text; light budget card is slate #445365 with white text.
- Income and category cards use reference gray #36343C in dark mode and #E6E0E9 in light mode; positive expense execution remains teal, distinct from the violet controls.
- New palette unit tests pass.
- Existing dialog screenshot tests remain green and dark modal surface/field colors remain graphite.
- GitHub Actions unit tests, signed Release APK build and signature verification pass.
- UI screenshot evidence is reviewed in both themes when an emulator/device runner is available. Pixel 8 manual check is still required before visual acceptance.

# ThunBudget reporting-cycle and cash-flow model

Version: 1.1  
Date: 2026-11-27  
Working branch: `fix/reporting-cycle-analytics-redistribution-2026-11-27`

## 1. Primary budget period

The primary "reporting month" is the complete two-period cycle selected by the configured reporting boundaries.

With the default 9–23 setting:

- Period 1: 9th–23rd of the selected month.
- Period 2: 24th of the selected month–8th of the next month.
- Reporting month: 9th of the selected month–8th of the next month.

All boundaries are inclusive. The first and second periods are consecutive and non-overlapping. A selected month label identifies the cycle start month, not the calendar month.

A separate calendar-month view (1st–last day) remains available for comparisons and trend analysis. It must be labelled as a calendar-month view and must not feed the primary reporting-month totals.

## 2. Income and available budget

Two different values must be kept distinct:

1. Earned income: new regular and additional receipts recorded in the two reporting periods. Carryover is excluded.
2. Available period budget: earned receipts plus money carried into that period from the preceding period.

For each exact reporting period P:

`earned(P) = sum of income entries in P excluding explicit carryover entries`

`available(P) = recordedIncomeAmount(P) + unrecordedLegacyCarryover(P)`

The recorded amount already includes explicit carryover entries. The legacy carryover calculation is added only when no explicit period transfer has already closed the source period; this prevents counting the same carryover twice.

For the selected two-period cycle:

`reportingMonthEarnedIncome = earned(P1) + earned(P2)`

`reportingMonthAvailableBudget = available(P1) + available(P2)`

The primary income figure reports earned income. Carryover is shown separately and affects category plan amounts because it is money available to spend in the receiving period. A configured fixed monthly income is used as a plan fallback only when neither reporting period has an entered income amount; a missing second period is not fabricated when the first period is already recorded.

## 3. Category budget plans

Each period uses its own saved category-share snapshot. The two periods must not be assigned one identical percentage when their saved shares differ.

For category C:

`cyclePlan(C) = available(P1) × share(P1,C) + available(P2) × share(P2,C)`

This is equivalent to weighting the two period percentages by their available budgets. It is not an unweighted average and is not a calendar-day prorating operation.

If one period's income has not been entered, its contribution is zero. If both periods have no income, the configured fixed monthly income is used as a fallback with the current category setup.

## 4. Actual expenses and daily average

Primary reporting-month actuals use transaction dates within:

`[P1.start, min(today, P2.end)]`

The total and category facts use the same date interval. The daily average is:

`actual cash expenses in cycle ÷ elapsed inclusive days from P1.start to actual cutoff`

For a completed historical cycle, the cutoff is P2.end. For a future cycle that has not started, actual expenses are zero.

ThunBudget uses the selected available-card-balance policy:

- Purchases and bills are cash outflows.
- Transfers from the main spending balance to savings/reserve remain outflows.
- Money returned from savings is a separate dated additional-income entry; it does not erase the original savings outflow.
- The technical `Перенос средств` transaction is an internal period-closing allocation. It remains visible in operation history and period closure, but is excluded from reporting-month cash expenses.

## 5. Dynamic redistribution

Redistribution recommendations apply to the explicitly selected reporting period, not to an unrelated calendar-month aggregate. They use that period's actual expenses, period-available budget, category shares and remaining-period forecast.

- A category with actual spending above its plan is a redistribution target.
- Donors are ordered by projected free balance: at least 20%, then at least 15%, then at least 10%, then greater than 5%.
- Categories with 5% or less projected free balance are never used as donors.
- The reserve category is considered before ordinary donors when it has available funds.
- Savings is not silently excluded as a donor; its eligibility is determined by the same free-balance threshold.
- The technical `Перенос средств` row is neither a target nor a donor.
- Applying a proposal updates the selected period's saved plan; it does not rewrite the other period's distinct snapshot.

The user confirms a proposed redistribution before the app changes category shares.

## 6. Secondary calendar and annual views

The calendar-month mode keeps its separate 1st–last-day calculations and forecast. The calendar view must not be used to populate the primary reporting-month cards.

The year summary remains a calendar-year report from January 1 through the current date. The Excel `Месяцы` sheet follows the primary reporting cycle; the annual sheet remains a calendar-year summary. The raw Operations sheet preserves all transactions, including technical carryover rows.

## 7. Regression coverage

Tests cover:

- the two full periods being summed without prorating across calendar month boundaries;
- carryover being added to available period funds without double counting explicit transfer entries;
- legacy carryover being added only when no explicit transfer is recorded;
- reporting-cycle actuals using both periods and stopping at today;
- category plans preserving different saved percentages for the first and second periods;
- technical carryover exclusion from cash-expense totals while savings and reserve outflows remain included;
- redistribution priority by 20%, 15%, and 10% thresholds, with categories at 5% or less excluded.

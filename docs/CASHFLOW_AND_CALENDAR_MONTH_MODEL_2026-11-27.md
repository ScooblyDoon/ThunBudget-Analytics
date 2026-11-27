# Cash-flow and calendar-month model

Version: 1.0  
Date: 2026-11-27  
Working branch: `feature/cashflow-calendar-ledger-2026-11-27`

## 1. Product accounting policy

ThunBudget uses a **cash available to spend from the main card / routine spending pool** model, not a bank-account net-worth model.

- Regular salary/base earnings are regular income.
- Bonuses, side earnings, refunds and money returned from a savings account to the main card are dated additional income.
- Money moved from the main card to a savings account, reserve or another unavailable pool is an expense/outflow. Savings and reserve categories remain in spending totals.
- Internal category reallocations are not income and are not expense transactions.
- The technical `Перенос средств` operation that moves the remaining budget from one reporting period to the next is an internal period allocation. It closes the prior period and opens the next period's budget, but it is not a new cash outflow from the main card and is excluded from calendar-month cash expenses.
- The original outflow to savings is not deleted when money is later returned. The return is recorded as a separate additional income transaction.

This policy is intentionally different from some account-ledger apps. In YNAB, a transfer between two accounts that are both included in the plan is an internal transfer; a transfer to an account outside the plan is treated as spending from the plan. Monarch excludes transfers between owned accounts from spending totals. ThunBudget follows the user's selected available-card-balance policy rather than copying either app wholesale.

## 2. Date model

There are two time axes and they must not be mixed:

1. **Reporting-period budget:** a configured first period followed by a second period. Example for 9–23: first period 9th–23rd; second period 24th–8th of the next month. Date bounds are inclusive and periods are consecutive.
2. **Calendar-month cash-flow report:** calendar dates from the 1st to the last day of the month. For the current month, actuals stop at today. A reporting period that crosses a month boundary contributes to each calendar month only for the dates falling inside that month.

For an inclusive reporting period (P=[s,e]):
- (D_P = e-s+1) calendar days.
- (D_{P,M}) is the number of dates in the overlap of that period and calendar month (M).

## 3. Income formulas

### Reporting-period available income

For reporting period (P):

`periodAvailableIncome = regularIncome(P) + additionalIncome(P) + carryoverIn(P)`

Carryover is not new earned income. It is a budget balance imported from the previous period.

### Calendar-month income allocation

The regular-income input stores one amount for the entire reporting period; it does not currently store the exact bank posting date of the salary. To make calendar-month budgets comparable when a reporting period crosses month boundaries, the app allocates the regular amount evenly across the inclusive calendar dates of that reporting period:

`regularIncomeAllocatedToMonth(M) = Σ regularIncome(P) × D(P,M) / D(P)`

This is a **calendar-month allocation for budget analysis**, not a claim that the salary was deposited on every one of those dates. Exact bank-statement cash-flow for regular salary would require a separate salary receipt date. The current product model deliberately uses period allocation for regular income, consistent with the configured reporting-period budget.

Additional income is different: it is a dated event, not a recurring daily rate:

`additionalIncomeInMonth(M) = Σ amount(entry), where entry.isAdditional and entry.date ∈ M`

Thus:

`calendarMonthIncomeEntered(M) = regularIncomeAllocatedToMonth(M) + additionalIncomeInMonth(M)`

The carryover from the preceding reporting period is not counted as new monthly income; the original income was already included when allocated to its original reporting-period dates.

### Monthly income forecast

- Use only regular/base earnings from completed reporting periods.
- Estimate a daily rate from the median of up to six most recent completed regular-income periods.
- Forecast only uncovered future calendar dates.
- Do not fill uncovered dates in the past.
- One-off additional income, including a return from savings, never raises the regular-income forecast baseline.
- An additional-only period does not mark every date in that period as covered by regular income.

`calendarMonthForecast(M) = medianCompletedRegularDailyRate × uncoveredFutureDays(M)`

`calendarMonthProjectedIncome(M) = calendarMonthIncomeEntered(M) + calendarMonthForecast(M)`

The report should label actual and forecast amounts separately. It must not present projected income as money already received.

## 4. Expense and transfer formulas

### Reporting-period budget balance

For the reporting-period plan, the service carryover transaction is retained because it explicitly closes the source period and pairs with carryover income in the next period:

`periodBudgetRemaining = periodAvailableIncome − periodBudgetOutflows`

The period-budget outflow total includes the explicit `Перенос средств` row so the same balance is not simultaneously left in the source period and added to the next period.

### Calendar-month cash expenses

Calendar-month cash expense totals are calculated by transaction date and include all real outflows, including:
- regular purchases and bills;
- transfers to savings that leave the user's available card pool;
- allocations to reserve/savings categories.

They exclude only the technical internal period-carryover category:

`calendarMonthCashExpenses(M) = Σ expense.amount`

for expense transactions whose date is inside (M), excluding category `Перенос средств`.

`calendarMonthNetCashFlow(M) = calendarMonthActualIncome(M) − calendarMonthCashExpenses(M)`

If a projected net cash-flow value is shown, use projected income and projected expenses as separate terms and label it as a forecast. Do not silently mix actual and forecast.

### Double-counting guardrails

- Do not count the same savings return both as a refund that reverses the original expense and as an independent additional income; it is one dated additional-income record.
- Do not count carryover as new earned income in calendar-month income.
- Do not count the internal carryover expense in calendar-month cash outflows.
- Do not remove savings/reserve outflows from the user's monthly expenses.
- A transfer/payment against a credit card or account can be treated differently depending on whether the original purchase was already recorded. If future account-level sync is introduced, it needs a separate linked-transfer model to avoid counting both the purchase and repayment. This change does not introduce bank-account synchronization.

## 5. Category budget and presentation

- Reporting-period category plans use the plan snapshot saved for that exact reporting period. When no historical snapshot exists, use the current configured category shares as the migration fallback.
- For calendar-month planned category amounts, allocate each reporting period's regular income over its calendar days and apply the saved category share for that period. Additional income with a date is allocated using that period's category share on the receipt date.
- Actual month category spending uses calendar-month cash expenses (same exclusion rule for internal carryover as the total).
- Savings and reserve category outflows remain visible as spending under this product policy.
- A category exceeding its cap is a category-level variance, not proof that the total period or month exceeded the overall available budget. Show the category balance and overall period/month balance separately.
- For a current month, an on-pace warning can use elapsed-day pace or the forecast; red "over budget" should be reserved for an amount that actually exceeds the relevant cap. Forecast overspend should be labelled as projected, not actual.

## 6. Current implementation scope

Implemented on the working branch:
- Calendar-month income can receive the dated income ledger so additional income is assigned to its actual transaction month.
- Monthly category budget allocation can receive the dated ledger and assigns additional receipts on their actual dates.
- Legacy aggregate additional-income amounts without dated ledger detail retain a compatibility fallback.
- Dedicated calendar-month cash expense helpers exclude internal `Перенос средств` only; savings/reserve remain included.
- Monthly and yearly analytics totals/charts use the calendar cash-expense basis.
- The monthly income selector shows income allocated from entered reporting periods, the forecast for uncovered future dates, and the combined expected income as separate figures.
- The Excel monthly category sheet uses the same cash-expense basis and saved period-specific category shares as monthly analytics. The annual sheet uses the app's existing year-to-date plan model, while its actual amounts exclude internal carryover. The raw Operations sheet keeps every transaction visible for audit, including technical carryover rows. The period report retains carryover for reporting-period closure.
- Reporting-period carryover calculation and per-period budget closure remain unchanged.

## 7. Regression tests

The accompanying tests cover:
- a reporting period crossing a month boundary;
- a dated savings return assigned only to the month in which it arrived;
- additional-only income not marking an entire period as covered by regular income;
- category plan allocation of a dated additional receipt;
- monthly cash expenses excluding internal period carryover while retaining savings/reserve outflows;
- existing carryover tests to ensure period budget closing continues to work.

# ThunBudget Analytics 1.3.17 — Financial assistant

## Added
- Identify the calendar date with the highest total spending.
- Identify the weekday with the highest total spending.
- Rank categories by total spend, not only by transaction count.
- Give local savings suggestions based on category budgets and actual expenses.
- Include active, uncompleted recurring payments due before the end of the selected reporting period in the available-balance estimate.
- Understand natural-language category aliases such as bus/taxi for Transport, pharmacy/medicine for Health, and groceries/food for Products when matching configured categories.
- Rank alternative speech transcripts by the existing local relevance score before parsing.

## Safety and privacy
- No cloud AI service or remote language model was added.
- Financial calculations run locally against the existing expense, category, income, and recurring-payment data.
- Recommendations do not edit the budget or transactions; they are advisory only.
- Android on-device speech recognition remains device/service dependent.

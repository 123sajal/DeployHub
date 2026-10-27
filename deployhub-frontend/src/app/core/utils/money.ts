/** Formats an amount in the currency's smallest unit (paise for INR): 4900 -> "₹49", 4950 -> "₹49.50". */
export function formatPrice(amount: number, currency: string): string {
  const value = amount % 100 === 0 ? String(amount / 100) : (amount / 100).toFixed(2);
  return currency === 'INR' ? `₹${value}` : `${value} ${currency}`;
}

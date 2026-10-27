import { formatPrice } from './money';

describe('formatPrice', () => {
  it('shows rupees from paise, with decimals only when needed', () => {
    expect(formatPrice(4900, 'INR')).toBe('₹49');
    expect(formatPrice(4950, 'INR')).toBe('₹49.50');
    expect(formatPrice(1999, 'USD')).toBe('19.99 USD');
  });
});

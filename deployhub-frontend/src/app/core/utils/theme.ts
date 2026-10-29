import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/**
 * Aura with DeployHub's warm orange as primary, matching the CSS tokens in styles.scss. 600 is a slightly deeper
 * orange than Tailwind's, so white text on buttons stays readable (4.6:1); 500 is the bright brand orange.
 * Light is the default; dark applies with the "app-dark" class on <html> (ThemeService).
 */
export const DeployHubPreset = definePreset(Aura, {
  primitive: {
    brand: {
      50: '#fff7ed',
      100: '#ffedd5',
      200: '#fed7aa',
      300: '#fdba74',
      400: '#fb923c',
      500: '#f97316',
      600: '#cc4a0a',
      700: '#b93d0a',
      800: '#9a3412',
      900: '#7c2d12',
      950: '#431407',
    },
  },
  semantic: {
    primary: {
      50: '{brand.50}',
      100: '{brand.100}',
      200: '{brand.200}',
      300: '{brand.300}',
      400: '{brand.400}',
      500: '{brand.500}',
      600: '{brand.600}',
      700: '{brand.700}',
      800: '{brand.800}',
      900: '{brand.900}',
      950: '{brand.950}',
    },
    colorScheme: {
      light: {
        primary: {
          color: '{primary.600}',
          contrastColor: '#ffffff',
          hoverColor: '{primary.700}',
          activeColor: '{primary.800}',
        },
        surface: {
          0: '#ffffff',
          50: '#fdfaf6',
          100: '#f7f1ea',
          200: '#ede7e0',
          300: '#ddd4ca',
          400: '#a8a29e',
          500: '#78716c',
          600: '#57534e',
          700: '#44403c',
          800: '#292524',
          900: '#1c1917',
          950: '#0c0a09',
        },
      },
      dark: {
        surface: {
          0: '#ffffff',
          50: '#e6e8ee',
          100: '#c9cdd8',
          200: '#a3a9b9',
          300: '#8b92a7',
          400: '#5d6478',
          500: '#3a4054',
          600: '#2e3448',
          700: '#232838',
          800: '#161a23',
          900: '#11141b',
          950: '#0b0d12',
        },
      },
    },
  },
});

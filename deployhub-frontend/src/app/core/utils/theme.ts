import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/** Aura with an indigo primary to match DeployHub's own CSS tokens in styles.scss. */
export const DeployHubPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{indigo.50}',
      100: '{indigo.100}',
      200: '{indigo.200}',
      300: '{indigo.300}',
      400: '{indigo.400}',
      500: '{indigo.500}',
      600: '{indigo.600}',
      700: '{indigo.700}',
      800: '{indigo.800}',
      900: '{indigo.900}',
      950: '{indigo.950}',
    },
    colorScheme: {
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

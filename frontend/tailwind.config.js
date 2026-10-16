/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        background: "#0B0E14",
        surface: "#141A24",
        "surface-elevated": "#1A2230",
        border: "#242E42",
        "border-light": "#2D3A52",
        "primary-text": "#F8FAFC",
        "secondary-text": "#94A3B8",
        "muted-text": "#64748B",
        accent: {
          DEFAULT: "#0EA5E9",
          hover: "#0284C7",
          subtle: "rgba(14, 165, 233, 0.12)"
        },
        success: {
          DEFAULT: "#10B981",
          subtle: "rgba(16, 185, 129, 0.12)"
        },
        warning: {
          DEFAULT: "#F59E0B",
          subtle: "rgba(245, 158, 11, 0.12)"
        },
        emergency: {
          DEFAULT: "#EF4444",
          subtle: "rgba(239, 68, 68, 0.15)",
          border: "#DC2626"
        }
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
      }
    },
  },
  plugins: [],
}
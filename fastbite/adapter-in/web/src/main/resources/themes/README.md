# Restaurant themes

The guest and staff pages (menu, payment, confirmation, counter, kitchen, tickets) have one base stylesheet,
`static/css/style.css` (the back office shares it until it moves), and one theme package per look. Each page carries
`data-theme="<id>"` on `<html>` and links the location's `theme.css` after the base.

- **The base** holds layout and components, written against the tokens below. It sets every token's default once, on
  `:root`, and never names a theme.
- **A theme** sets token values, scoped to `[data-theme="<id>"]`. It may also override components, under the same
  scope. Templates, JS and Java never change for a theme.

## Tokens

| Token | Styles | Default |
|---|---|---|
| `--color-primary` | Brand colour: quantity and price buttons, active category, filter, table and allergen, hover and focus borders, the confirmation rule | `#dc3545` |
| `--color-primary-hover` | Quantity buttons and the counter's main buttons on hover | `#c82333` |
| `--color-on-primary` | Text and icons on the primary colour or the brand gradient | `#fff` |
| `--color-accent` | Second stop of the brand gradient; the floating cart button and the login button | `#e85d75` |
| `--color-success` | Active payment method, available table, completed step | `#28a745` |
| `--color-info` | Change due in the counter payment | `#17a2b8` |
| `--color-warning` | Table waiting for the bill | `#ff9f43` |
| `--color-danger` | Occupied table | `#dc3545` |
| `--color-bg` | Page background | `#FFFDF9` |
| `--color-surface` | Cart items, counter product cards, allergen toggles, ticket figures | `#FFFFFF` |
| `--color-surface-tint` | Quantity pill, counter search field, selected payment option | `#FFFDF9` |
| `--color-surface-alt` | Neutral panels: order summary, cart totals, customization rows, category filter on hover | `#f8f9fa` |
| `--color-text` | Body text, cart items, allergen filter and toggles | `#212529` |
| `--color-text-strong` | Headings, cart total, product names and prices on the counter, cart count badges | `#212529` |
| `--color-text-muted` | Secondary text: category filters, allergen notes, ticket figure labels, cash check labels | `#6c757d` |
| `--color-border` | Neutral borders: cart, search field, table buttons, order cards, quantity buttons, summary box | `#dee2e6` |
| `--color-sidebar` | Counter category sidebar (top of its gradient), cart header, back-office field labels | `#2c3e50` |
| `--gradient-brand` | Top bar, active category, the counter's main and add buttons | `linear-gradient(135deg, var(--color-primary), var(--color-accent))` |
| `--font-body` | All text, headings included | `'Outfit', 'Segoe UI', sans-serif` |
| `--radius` | Corners of cards, cart items, price tags, the counter's header, search field and buttons, menus and dialogs | `20px` |
| `--shadow-sm` | Top bar and cart items | `none` |
| `--shadow-card` | Product cards, ticket figures | `0 4px 15px rgba(0, 0, 0, 0.05)` |
| `--shadow-card-hover` | Product card on hover, confirmation card | `0 8px 25px rgba(0, 0, 0, 0.1)` |
| `--layout-sidebar-width` | Counter category sidebar | `250px` |
| `--layout-cart-width` | Counter cart column | `380px` |

No theme ships a font file: `Outfit` and `Inter` are not installed, so the fallbacks (`Segoe UI`, `Georgia`,
`Helvetica Neue`) are what guests see.

## Adding a theme

1. Create `themes/<id>/` (`<id>`: lowercase letters, digits and `-`), in the classpath or in the directory named by
   `fastbite.themes.directory`.
2. Add `theme.properties` (`id`, `name`, `description`), `theme.css` and the three previews `menu.svg`, `counter.svg`
   and `kitchen.svg`. A package missing any of them stops the application at startup.
3. In `theme.css`, set the tokens you change, and only those, under `[data-theme="<id>"] { ... }`. Component
   overrides are allowed under the same scope; keep them few.
4. Restart. The theme appears in the owner's appearance settings; its stylesheet is served at
   `/images/themes/<id>/theme-<hash>.css`.

`ThemeContractTest` checks that every theme sets only these tokens, that the base names no theme, and that this table
lists every token the base defines.

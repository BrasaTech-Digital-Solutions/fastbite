# Google Stitch prompts: home page and onboarding (production and sandbox)

How to use:
1. Start every Stitch session by pasting **Prompt 0 (Design brief)**.
2. Then paste **one** screen prompt at a time (1 to 7), in order. Stitch keeps the style across screens in a session.
3. Ask for **mobile and desktop** of each screen. Owners mostly set up on a phone.

Copy is in English; the product ships in Spanish (primary), English and Portuguese. Ask Stitch to leave about 30%
extra room for Spanish text.

---

## Prompt 0: design brief (paste first, every session)

```
You are designing FastBite, a web app for independent restaurants, cafés and takeaways in Spain. The owners are
not tech people. FastBite gives them QR table ordering, a counter till (POS), a kitchen screen, online takeaway,
allergen information and legal tickets ready for Spain's VERI*FACTU invoicing rules.

The key idea of the product: there are TWO environments, and the owner must always know which one they are in.
- PRODUCTION ("FastBite", live): the real restaurant. Real orders, real payments, real legal invoices sent to
  the tax agency. This is where the owner signs up, subscribes and pays.
- SANDBOX ("Practice space"): a safe copy where the owner sets the restaurant up and practises. Choose a
  concept, build the menu, tables and team, try orders. Nothing there is real: no real payments, invoices or
  orders. When ready, the owner "promotes" the setup to production.
Production is the front door. Every location created in production automatically gets a twin practice space in
the sandbox. The owner enters the sandbox from production with one click and no second password.

Brand and tone: warm, calm, hospitable, "a little more flow, every day". Friendly, plain language, never
technical. Short sentences. Encourage, don't lecture.
Visual style: warm off-white background (#FBF8F2), deep green primary (#2F4A3A), terracotta accent (#C2643B),
soft neutral greys, generous whitespace, rounded cards (12px radius), subtle shadows, serif display headings
(e.g. Fraunces) with a clean sans body (e.g. Inter). Food photography in soft natural light.
Accessibility: WCAG AA contrast, visible focus states, 44px minimum touch targets, never use colour alone to
carry meaning.

Environment identity, used on every screen:
- Production: a small pill "● Live" in deep green, next to the location name.
- Sandbox: a full-width sticky striped banner (diagonal yellow #FFD23F / #FFC61A stripes, dark text #1C1A14,
  2px dark bottom border) with a boxed label "SANDBOX", the text "Practice environment: nothing here is real.
  No real payments, invoices or orders." and a link "Go to FastBite →". Plus a yellow pill "Practice" next to
  the location name. The browser tab title starts with "[Sandbox]".
- The two must never be confused, even in a screenshot or at a glance on a phone.

Generate clean, semantic, responsive HTML with real form fields (names given in each prompt), no placeholder
lorem ipsum.
```

---

## Prompt 1: home page (the funnel)

```
Design the public home page of FastBite (route "/"), whose job is to turn a restaurant owner into a signup.

Sections, top to bottom:
1. Header: FastBite logo, links (How it works, Features, Pricing, FAQ), "Sign in" (→ /login) and the primary
   button "Start free" (→ /signup).
2. Hero: headline "Less admin. A smoother service." Subhead: orders from table QR, counter and takeaway in one
   place, legal tickets included. Primary CTA "Start free: no card needed" and a secondary "See how it works".
   Visual: a phone showing a guest menu next to a tablet showing the counter.
3. How it works, 3 steps, with the sandbox/production idea made simple and reassuring:
   ① Sign up and create your restaurant (2 minutes).
   ② Practise in your sandbox: pick a concept (fast food, café, fine dining), we preload a realistic menu and
     style, and you try everything with fake orders. Nothing is real yet.
   ③ Go live: bring your setup to your real restaurant in one step and start trading.
   Show a small "Practice ⟶ Live" illustration using the two environment pills.
4. Features grid (6 cards): QR ordering at the table; counter till with cash and card; kitchen screen with
   live orders and timers; online takeaway with prepayment; the 14 EU allergens on every dish; legal tickets and
   VERI*FACTU-ready invoicing.
5. "A good day" story strip: morning to close, showing the counter, kitchen and end-of-day report.
6. Multi-location: "Your locations. One login." Each location has its own menu, team, legal details and
   subscription.
7. Pricing ("One plan."), two cards:
   - Free: 0 €/month, online takeaway card orders carry a 2.5% platform fee.
   - Restaurant: 49 €/month per location excluding VAT, 30-day free trial, no card required.
   Note: "Three locations or more? Ask for volume pricing."
8. FAQ: Is the sandbox really free? Do I need to know anything technical? Is it legal for Spain (VERI*FACTU)?
   Can I bring my existing menu? What happens when the trial ends?
9. Final CTA band "Start free" and a footer (legal notice, privacy, cookies, terms, contact).
```

---

## Prompt 2: signup and sign-in (production)

```
Design the production signup page (route "/signup", form POST to "/signup") and sign-in page (route "/login",
form POST to "/login"). Both show the "● Live" FastBite identity (no sandbox banner).

Signup, one short form, friendly and fast:
- "Your restaurant's web address", field name "tenantId": 1 to 56 letters or digits, live preview
  "fastbite.brasatech.es/<address>/menu", help "e.g. cornercafe". Some words are reserved (admin, menu, login…): show the
  server error inline.
- "Your name", field "fullName"
- "Username", field "username" (letters, digits, . _ @ -)
- "Password", field "password": at least 10 characters, show/hide toggle, strength hint
- Plan, field "plan": radio cards "Free" (value FREE) and "Restaurant: 30-day trial" (value RESTAURANT, default)
- Hidden field "_csrf"
- Button "Create my restaurant"
Success state: "Your restaurant is ready. We're preparing your practice space…", with a button "Sign in".
Error state: one clear message above the form, the fields kept.

Sign-in: "username", "password", "_csrf", button "Sign in", link "Create a restaurant". Error state "Invalid
username or password". After sign-in the owner lands on the owner workspace (Prompt 3).

Also design the sandbox's sign-in page: when someone opens the sandbox directly it does not ask for a password.
It shows the sandbox banner and a single card "Owners enter the practice space from FastBite" with the button
"Go to FastBite" (→ production "/owner/sandbox"). Staff of a location do sign in here, with a small "Staff
sign-in" form (username, password).
```

---

## Prompt 3: owner workspace shell and production overview (new location)

```
Design the owner workspace in PRODUCTION ("● Live"), route "/owner/console?location=<id>".

Shell (shared by every workspace screen):
- Left sidebar (collapses to a bottom tab bar on mobile): Overview, Locations, Team, Location settings,
  Account & billing. Footer "A little more flow. More time for your guests."
- Top bar: location switcher (select of the owner's locations), the environment pill "● Live", the button
  "Practise in the sandbox ↗" (opens "/owner/sandbox?location=<id>" in a new tab), owner name and "Sign out"
  (POST /logout).
- Notice area under the top bar for success (green) and error (red) messages.

Overview of a NEW location (nothing promoted yet), in production:
- Heading "Let's get <location> ready." and a status pill "Free trial · 27 days left · Billing →"
  (→ /owner/billing/<id>).
- Main card "Set up <location> in your practice space": choose your concept, build your menu, tables and team,
  and practise a service. Nothing there is real. Buttons: "Open the practice space ↗" (primary) and
  "Restore my production bundle" (secondary, → Location settings > Backup & restore).
- A 3-step journey indicator: ① Practise in the sandbox (current) → ② Bring your setup here → ③ Open for
  business.
- States of the practice space, shown as a small status line on the card:
  ready ("Your practice space is ready"), being prepared ("Your practice space is being prepared. Try again in
  a minute."), and closed ("Your trial or subscription has ended. Renew it in Billing to practise again.").
- Locations view (route "?view=locations"): cards per location (name, plan, links to its guest menu
  "/<id>/menu" and "Trial & billing"), plus "Add a location" (form POST "/owner/add-location", field
  "tenantId"): "Each location gets its own 30-day trial."
```

---

## Prompt 4: sandbox workspace and concept onboarding

```
Design the owner workspace in the SANDBOX (practice space), same shell as production but unmistakably
different: the striped SANDBOX banner on top, the yellow "Practice" pill instead of "● Live", and no "Add a
location", billing or custom-domain actions. Those become links "… in FastBite ↗" to production.

Overview of a new practice space, route "/owner/console?location=<id>":
1. Welcome card "What kind of restaurant are you running?" with three large selectable concept cards, each
   with a photo, a short description and its visual theme name:
   - Casual / Fast Food: smashburgers, loaded fries, sodas (template "kebab", theme "fastfood")
   - Specialty Café & Bakery: flat whites, sourdough brunch, bakery treats (template "cafe", theme "cafe")
   - Fine Dining & Bistro: tasting menus, wine pairings (template "michelin", theme "modern")
   A live preview of a sample dish updates with the choice. A small animation compares "traditional waiter
   running: 14 min" with "FastBite: ticket in the kitchen in 0.3 s".
   Form POST "/owner/workspace/<id>/setup" with fields action=adopt, template, theme, _csrf. Button
   "Apply concept". Secondary: "Bring my backup ZIP".
2. After a concept is applied: the "Opening checklist" with progress (e.g. "2 of 4 ready"):
   ① Your menu: "18 menu items added" → Edit menu ("/<id>/backoffice")
   ② How you serve: table service, takeaway, kitchen timings → Location settings
   ③ See it as your guest: preview the guest menu in a phone/desktop frame ("Orders placed here are practice
     orders.")
   ④ Your business details (legal name, NIF, address, VAT): needed before going live
   "Hide checklist" (action=dismiss) and "Show opening checklist" (action=resume).
3. "Your daily tools" cards: Open counter ("/<id>/counter"), Open kitchen ("/<id>/dashboard"), View guest menu
   ("/<id>/menu"), each with a small illustration in the chosen theme.
4. "Ready to open?" card, "Take your setup to production", with two numbered steps:
   ① Export your production bundle here (→ Location settings > Backup & restore). It carries your menu,
     settings, look and business details; never practice orders, staff accounts or payment settings.
   ② In FastBite, restore it into <location> (link to production, opens in a new tab).
```

---

## Prompt 5: entering the sandbox (link states)

```
Design the small interstitial pages shown in the SANDBOX when an owner arrives from production with a one-time
sign-in link (route "/link/enter"). Normally the owner never sees them: they are signed in and sent straight to
their practice space. Show the sandbox banner and one centred card per state, with the button "Open the
practice space again" (→ production "/owner/sandbox"):
- Link expired or already used: "This link has expired or was already used. Open the practice space again from
  FastBite."
- Being updated: "The practice space is being updated to a new version. Try again in a minute."
- Not ready yet: "Your practice space is still being prepared. Try again in a minute."
Also design a brief loading state "Opening your practice space…" for the production side while the link is
created.
```

---

## Prompt 6: promotion: export in the sandbox, restore in production

```
Design the promotion flow across both environments, inside Location settings > "Backup & restore".

In the SANDBOX (banner and Practice pill):
- Card "Take your setup to production": what goes (menu, categories, products, options, tables, discounts,
  settings, look, business details, images) and what stays (practice orders, staff accounts, payment
  settings). Button "Export production bundle" (downloads a ZIP from
  "/<id>/api/backoffice/maintenance/export-production-bundle").
- Card "Your data, ready to take with you": "Download full backup ZIP".

In PRODUCTION (● Live):
- Card "Bring in your practice setup", with the 2 steps and the button "Open the practice space ↗".
- Restore panel: file picker "FastBite backup ZIP" → "Check backup" (multipart POST
  "/owner/workspace/<id>/backup-preview", field "file") → a summary "18 products · 0 orders · 0 accounts"
  and a warning that restoring replaces this location's menu, settings and files → "Review and restore", which
  opens a confirmation dialog requiring the owner to type the location address, then a multipart POST
  "/<id>/api/backoffice/maintenance/restore" with header X-CSRF-TOKEN.
- Error state for a bundle from another version: "This bundle was made by another version of FastBite. Export
  it again from your practice space." (HTTP 409).
- Success: "Your setup is live. Check your business details before your first sale." → links to the legal
  details.
```

---

## Prompt 7: production readiness after promotion

```
Design the PRODUCTION overview once a location has been promoted ("● Live"). Its job is to guide a
non-expert owner through what the law and payments require before the first real sale.

- Heading "Welcome back." or "Almost ready to open." and the billing status pill.
- "Before your first real sale" checklist, each item with status, a one-line reason and a button:
  ① Business details (legal name, NIF/NIE, address, postal code, city, province, ticket series prefix, default
    VAT rate) → form POST "/owner/workspace/<id>/legal". Required: tickets cannot be issued without them.
  ② VERI*FACTU: invoicing system declaration (view "/verifactu/declaracion-responsable") and record status.
  ③ How you serve: table service, takeaway, kitchen timings → POST "/owner/workspace/<id>/service".
  ④ Diner payments for online takeaway: connect Stripe (POST "/owner/connect-stripe"), with status
    "Not connected" / "Verification pending" / "Active & verified".
  ⑤ Your team: add staff with roles cashier, waiter, cook, manager (POST "/owner/locations/<id>/users").
  ⑥ Starting cash in the drawer (POST "/owner/workspace/<id>/cash-float", default 0 €).
- "Your daily tools" (counter, kitchen, guest menu) become prominent once ① is complete.
- A small persistent "Practise in the sandbox ↗" link, for trying changes safely before doing them live.
```

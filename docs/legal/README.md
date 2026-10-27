# Legal texts: drafts for review

FastBite's legal pages are **drafts written for a lawyer and an advisor to review**, not reviewed texts. They are live so
the product is complete, and they describe what the app really does (checked against the code on 2026-10-16).

Spanish is the binding version. English and Portuguese are translations and say so.

| Page | Address | Template | Law it answers |
|---|---|---|---|
| Aviso legal | `/legal/notice` | `templates/fastfood/legal/notice.html` | LSSI-CE art. 10 |
| Política de privacidad | `/legal/privacy` | `…/legal/privacy.html` | RGPD arts. 13–14, LOPDGDD |
| Política de cookies | `/legal/cookies` | `…/legal/cookies.html` | LSSI-CE art. 22.2, AEPD cookie guide |
| Condiciones del servicio | `/legal/terms` | `…/legal/terms.html` | B2B contract (Código Civil, Código de Comercio) |
| Contrato de encargo del tratamiento | `/legal/data-processing` | `…/legal/data-processing.html` | RGPD art. 28.3, LOPDGDD art. 33 |
| Privacidad para clientes de cada restaurante | `/<restaurant>/privacy` | `…/legal/guests.html` | RGPD arts. 13–14 (the restaurant is the controller) |

Any page accepts `?lang=es|en|pt`; otherwise it follows the browser's language (Spanish when unsupported).

They are linked from:
- the home page footer;
- the signup form, which states that creating a restaurant accepts the terms and the processing agreement;
- the owner console footer;
- each guest menu ("Privacy", next to the allergen chart).

## Company details (one place)

`webapplication/src/main/resources/application.yml`, `fastbite.legal.*`, each overridable by environment variable:

| Setting | Variable | Current value |
|---|---|---|
| company-name | `LEGAL_COMPANY_NAME` | BRASATECH DIGITAL SOLUTIONS, S.L.U. (sole-member company: LSC art. 13.2) |
| tax-id | `LEGAL_TAX_ID` | B09767898 |
| address | `LEGAL_ADDRESS` | Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España (domicilio social as registered) |
| email | `LEGAL_EMAIL` | info@brasatech.es |
| registry | `LEGAL_REGISTRY` | Registro Mercantil de Alicante, tomo 4454, folio 21, sección 8.ª, hoja A-178766, inscripción 1.ª |
| website | `LEGAL_WEBSITE` | https://fastbite.brasatech.es |
| updated | `LEGAL_UPDATED` | 27/10/2026 (change it whenever a text changes) |

The plan prices in the terms (49 €/month per location, 30-day trial, 2.5% Free-plan fee) come from `SubscriptionPlan`,
so they always match what the app charges. The cookie policy shows the session cookie name as configured.

## Before going live: to fill in or confirm

1. **Registry data: done** (27/10/2026), from the Registro Mercantil's listing of the constitution (inscripción 1.ª,
   08/03/2022): Alicante, tomo 4454, folio 21, sección 8.ª, hoja A-178766.
2. **Contact email** for legal and privacy requests: `info@brasatech.es` is assumed. A dedicated address
   (privacidad@…) is common.
3. **Firebase Hosting and the session cookie.** Firebase Hosting forwards only a cookie named `__session` to Cloud Run.
   Production and the sandbox will need `FASTBITE_SESSION_COOKIE=__session`, or nobody stays signed in. Each
   environment must also keep its cookie to its own host (no shared cookie domain), so the two `__session` cookies
   don't collide. The cookie policy follows the configured name automatically.
4. **Google Fonts and the CDNs: resolved.** A German court (LG München, 2022) fined a site for embedding Google Fonts
   without consent, since they receive visitors' IP addresses. FastBite now serves its fonts, icons and libraries
   itself (see `docs/third-party-assets.md`), and the privacy and cookie policies say so. Keep it that way: a new
   page must not load anything from a third-party host.
5. **Log retention: done** (27/10/2026). Cloud Logging keeps request logs 30 days, and the policy now says 30 days.
   The log buckets are in the `global` location, not Madrid, so the policy lists Cloud Logging among the services that
   may handle data outside the EEA. Routing the logs to a bucket in `europe-southwest1` would let it say Madrid.
6. **Commercial communications: opt-in** (advisor, 27/10/2026). Offers are sent only to owners who ticked the
   optional, unticked signup box; the consent's date and version are stored in `public.legal_acceptances`. Data stays
   within Brasatech, for its own products. Every offer email must carry an unsubscribe link, and withdrawals sent to
   privacidad@brasatech.es must be honoured. Guests' data is never used for offers.
7. **Privacy mailbox.** The policy sends people to `privacidad@brasatech.es` (`LEGAL_PRIVACY_EMAIL`). It must exist.

## The advisor's answers (27/10/2026)

The dossier `FastBite-documentacion-legal-2026-10-27.pdf` asked numbered questions; what was not commented on was
accepted as written. The answers:

| Id | Answer | Where it lives |
|---|---|---|
| A1 | VERI*FACTU keeps "BRASATECH DIGITAL SOLUTIONS S.L."; the legal texts say S.L.U. | `VERIFACTU_PRODUCER_NAME`, `LEGAL_COMPANY_NAME` |
| A2 | Privacy contact: privacidad@brasatech.es | `LEGAL_PRIVACY_EMAIL`, privacy policy |
| B1 | A terms checkbox at signup; store date and version | signup, `public.legal_acceptances` (version = `LEGAL_UPDATED`) |
| B2 | Businesses and autónomos only; no consumers | terms §1, signup checkbox |
| B7 | Unpaid: new orders paused 30 days, then the location's contract ends (§13 applies) | terms §5 and §13 |
| B8 | Free plan: online payments enabled on every channel offered; 2.5% on every online order | terms §4, signup |
| C4 | Each location can add and update its own guest privacy PDF | to build |
| D1 | Follow Orden HAC/1177/2024 art. 15 exactly | declaration page (items a–l, annex) |
| D2 | In the app, easy to find | links in the home footer, legal pages, owner console, signup |
| D3 | One signed declaration per software version | to build |
| D4 | Colaborador social; pilots may start with apoderamiento | terms §7 |
| D5 | R1 vs R4 for refunds: open | — |
| D6 | IndicadorMultiplesOT counts closed locations too | as built |
| E2, E4 | Marketing is opt-in; saying that offers are tailored is enough | privacy policy §5, signup |

Any change to a legal text must bump `LEGAL_UPDATED`: it is the version owners accept, and the date the pages show.

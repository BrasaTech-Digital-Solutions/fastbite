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
| company-name | `LEGAL_COMPANY_NAME` | BRASATECH DIGITAL SOLUTIONS S.L. |
| tax-id | `LEGAL_TAX_ID` | B09767898 |
| address | `LEGAL_ADDRESS` | Calle Ganaderos, 24, 03130 Santa Pola (Alicante), España |
| email | `LEGAL_EMAIL` | info@brasatech.es |
| registry | `LEGAL_REGISTRY` | **empty**: the notice shows "[Pendiente: Registro Mercantil…]" |
| website | `LEGAL_WEBSITE` | https://fastbite.brasatech.es |
| updated | `LEGAL_UPDATED` | 16/10/2026 (change it whenever a text changes) |

The plan prices in the terms (49 €/month per location, 30-day trial, 2.5% Free-plan fee) come from `SubscriptionPlan`,
so they always match what the app charges. The cookie policy shows the session cookie name as configured.

## Before going live: to fill in or confirm

1. **Registry data** (`LEGAL_REGISTRY`): Registro Mercantil de Alicante, tomo, folio, hoja e inscripción. LSSI-CE art. 10
   requires it.
2. **Contact email** for legal and privacy requests: `info@brasatech.es` is assumed. A dedicated address
   (privacidad@…) is common.
3. **Firebase Hosting and the session cookie.** Firebase Hosting forwards only a cookie named `__session` to Cloud Run.
   Production and the sandbox will need `FASTBITE_SESSION_COOKIE=__session`, or nobody stays signed in. Each
   environment must also keep its cookie to its own host (no shared cookie domain), so the two `__session` cookies
   don't collide. The cookie policy follows the configured name automatically.
4. **Google Fonts and the CDNs.** Several pages load Google Fonts, cdnjs and jsDelivr, which receive visitors' IP
   addresses. A German court (LG München, 2022) fined a site for embedding Google Fonts without consent. Hosting the
   fonts and libraries ourselves would remove the issue; until then the privacy and cookie policies disclose it.
5. **Log retention.** The privacy policy says technical logs are kept up to 90 days. Check the Cloud Logging retention
   configured in GCP and align either side.

## Points for the lawyer

- **Acceptance.** Signup states that creating a restaurant accepts the terms and the processing agreement (no tick
  box). Is that enough evidence for a B2B contract, or should we add a mandatory tick box and store the date and
  version accepted?
- **Businesses only.** The terms exclude consumers. Confirm, including sole traders (autónomos).
- **Liability.** The cap is the fees paid in the last 12 months, excluding wilful misconduct and gross negligence.
- **Jurisdiction.** The courts of Alicante, B2B only.
- **Price and terms changes:** 30 days' notice, with the right to cancel.
- **The end of the contract:** 30 days to export data, then deletion except what the law requires to be kept. The
  owner console's backup export is the export mechanism.
- **Trial and non-payment:** new orders pause while sign-in, billing, existing orders and export keep working, as the
  app behaves today.
- **VERI*FACTU.** The customer is the taxpayer. FastBite sends records only with representation (apoderamiento or
  colaborador social). The system's declaración responsable is a separate page, `/verifactu/declaracion-responsable`,
  with its own draft notice.
- **Stripe.** The Free plan's fee is an application fee withheld from the payment. Restaurants contract Stripe directly
  (Stripe Connect); Stripe is described as an independent controller for payments.
- **The guest notice.** The restaurant is the controller, and each location gets a generated notice from its business
  details. Confirm the legal bases (sale contract; invoicing obligations) and the retention periods (4 years tax,
  6 years Código de Comercio).
- **Sub-processors:** Google Ireland (Cloud Run, Cloud SQL, Cloud Storage, Firebase Hosting, Madrid region) and
  Microsoft Ireland (Microsoft 365 email), general authorisation with 30 days' notice of changes. Confirm the list is
  complete before going live, and keep it current.
- **No DPO.** None is appointed, on the basis that one isn't required. Confirm, given that we process diners' data for
  many restaurants.

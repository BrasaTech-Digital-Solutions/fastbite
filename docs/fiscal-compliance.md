# Fiscal compliance (Spain)

FastBite is built to replace a restaurant's certified till in Spain. This page explains how a location's
tickets are kept legally correct. It is product documentation, not legal advice: restaurants should confirm
their own obligations with their tax adviser (gestor).

## Business details per location

Every location has its own fiscal profile, stored in the location's own database schema. Two locations of
the same owner can belong to different businesses, so nothing fiscal lives on the owner account.

Owners fill it in under **Location settings → Business & tax**:

| Field | Required | Notes |
|---|---|---|
| Tax ID (NIF) | yes | DNI, NIE, company NIF or K/L/M personal NIF. The control character is checked as it is typed and again on the server. |
| Legal name | yes | Full name of a self-employed person, or the company name (razón social). The label adapts to the kind of NIF. |
| Trade name | no | Printed large at the top of the ticket. |
| Street, postal code, town, province | yes | The province is filled in from the first two digits of the postal code. |
| Phone, email | no | |
| Default VAT rate | yes | 10% (restaurants), 21% or 4%. Products may override it. |
| Series code | yes | 1–8 letters or digits that start every ticket number of the location, e.g. `KHC-T26-000001`. |

Owners can save an unfinished profile; wrong values are refused field by field. A location is only able to
issue legal tickets once every required field is filled in, and the opening checklist shows this as its
fourth step.

The Canary Islands, Ceuta and Melilla use IGIC or IPSI instead of VAT and are refused for now.

## VAT

Menu prices always include VAT. Each product can set its own rate (back office → product → VAT rate);
otherwise the location's default rate applies. When a guest or the counter orders, the rate is copied onto
the order line, so changing a product's rate later never changes past orders.

Totals show the VAT included per rate. The base is calculated backwards from what the guest pays and the
VAT is the difference, so base + VAT always equals the amount paid to the cent. Order-wide discounts are
shared over the rates in proportion to their amounts. See `VatBreakdown` in the domain module.

Voluntary tips are not part of the VAT base.

## Tickets

Every payment issues exactly one ticket, in the same transaction that marks the orders paid. Tickets are
never changed or deleted.

| Kind | Series | When |
|---|---|---|
| Simplified invoice | `PREFIX-T26` | Every payment, unless the customer asks for an invoice at the till |
| Invoice | `PREFIX-F26` | The cashier chose *Invoice* and entered the customer's tax ID, name and address |
| Invoice (replacement) | `PREFIX-F26` | *Tickets → Full invoice*: a customer asks for an invoice after paying; it replaces the simplified one |
| Corrective invoice | `PREFIX-R26` | *Tickets → Refund* (managers only): reverses a whole ticket, with a reason |

- Each location, kind and year has its own series, numbered from 1 without gaps. Numbers are taken inside
  the payment's transaction, so a failed payment never burns a number.
- The ticket stores a snapshot of the business details, the lines with their VAT rate and options, the VAT
  breakdown, the payment method, the cash received and change, the tip, the table and order numbers, and
  for online payments the Stripe payment reference.
- Paths: the counter pays through `POST /counter/api/settle`; tickets print from `/counter/tickets/{id}`;
  the day's tickets are at `/counter/tickets`.
- Online payments issue their ticket when Stripe confirms the payment; the tip is whatever was paid above
  the order total. Guests can only pay online when the location can issue tickets.
- A paid order can no longer be edited or cancelled: refund its ticket instead.
- The bill printed before paying is a pro forma marked "not an invoice".
- Once a location has issued a real ticket, its NIF and series code are locked and its business details
  must stay complete.
- Refunding a card or online payment gives the ticket back; the money itself is returned on the terminal or
  in Stripe.

## VERI*FACTU

FastBite works as a VERI*FACTU system: every ticket generates a billing record (registro de alta) in the
same transaction that issues it, so a ticket never exists without its record.

- **Chain**: each record holds the fingerprint (huella) of the record before it. The fingerprint is the
  SHA-256 of `IDEmisorFactura=…&NumSerieFactura=…&FechaExpedicionFactura=dd-mm-aaaa&TipoFactura=…&CuotaTotal=…&ImporteTotal=…&Huella=<previous>&FechaHoraHusoGenRegistro=<ISO with offset>`
  in uppercase hexadecimal. The implementation is checked against the two worked examples of the AEAT
  specification (`VerifactuTest`). Each location is one chain; the sandbox's training records form their own.
- **Invoice types**: simplified F2, full F1, full invoice replacing a simplified one F3, corrective R5 (of a
  simplified invoice) or R1 (of a full invoice). Confirm R1 vs R4 for refunds with the tax adviser.
- **QR code**: printed on every ticket, 34 mm, medium error correction, under "QR tributario:" and above
  "VERI*FACTU" and "Factura verificable en la sede electrónica de la AEAT". It points to the AEAT checking
  page (`www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR`), or to its test environment in the sandbox.
  The QR is drawn on the server as SVG.
- **Sending**: records are sent through a `VerifactuSubmitter`. The provider (or a direct connection) is not
  chosen yet, so `UnconfiguredVerifactuSubmitter` keeps every record pending; a background job sends pending
  records in chain order, at most 1000 at a time, once a submitter marked `@Primary` is added. The owner
  console shows the status (pending, accepted, refused with the reason) and lets the owner download every
  record as JSON, from which anyone can recompute the chain.
- **System**: the software is identified as `fastbite.verifactu.*` (producer name and NIF, system name, id and
  version). The producer must publish the declaración responsable for each version.

Records are generated for tickets issued after this was deployed; earlier tickets have none.

## Sandbox and production

`fastbite.environment` (`FASTBITE_ENVIRONMENT`) is `sandbox` unless set to `production`. The sandbox issues
training tickets in `TEST-` series, printed "training ticket — not valid as an invoice", even when business
details are missing. Production refuses payments until the location's business details are complete.
Defaulting to the sandbox means a misconfigured deployment produces visibly invalid tickets rather than
real invoices from test data.

## Backups and promotion

Backups (format 3) carry the fiscal profile. Restoring an older archive leaves the destination's profile as
it is. Production promotion bundles carry it as a draft for the owner to confirm. Resetting a location's
data keeps its business details and never deletes tickets. Tickets are not part of backups: they stay with
the location that issued them.

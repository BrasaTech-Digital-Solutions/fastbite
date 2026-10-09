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

## Backups and promotion

Backups (format 3) carry the fiscal profile. Restoring an older archive leaves the destination's profile as
it is. Production promotion bundles carry it as a draft for the owner to confirm. Resetting a location's
data keeps its business details.

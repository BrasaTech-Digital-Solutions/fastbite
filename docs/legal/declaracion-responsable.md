# Declaración responsable — FastBite (BORRADOR)

> **Draft for review.** Brasatech, as producer of FastBite, must sign a *declaración responsable* for each
> version of the software (Real Decreto 1007/2023 and Orden HAC/1177/2024). This text was drafted from the
> regulation as we understand it and has **not** been reviewed by a lawyer or tax adviser. Have it reviewed
> before signing.

The app shows the same text, filled in from configuration, at
`/verifactu/declaracion-responsable`. It is linked from the owner console (Location settings → Business &
tax → VERI*FACTU) and from the staff Tickets page, because the regulation asks for it to be readable from
the software itself. It shows a "Borrador" banner until it is signed and every detail is filled in.

## To do before signing

| Detail | Setting | Status |
|---|---|---|
| Producer's legal name | `VERIFACTU_PRODUCER_NAME` | BRASATECH DIGITAL SOLUTIONS S.L. (also sent in every billing record) |
| Producer's NIF | `VERIFACTU_PRODUCER_NIF` | B09767898 |
| Producer's full postal address | `VERIFACTU_PRODUCER_ADDRESS` | Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España |
| Version | `VERIFACTU_SYSTEM_VERSION` (default `1.0`) | Decide the versioning; a new declaration is needed per version |
| Place of signature | `VERIFACTU_DECLARATION_PLACE` | Santa Pola (Alicante) |
| Who signs | `VERIFACTU_DECLARATION_SIGNATORY` | Ricardo Maximino Gonçalves de Moraes, administrador único |
| Date of signature | `VERIFACTU_DECLARATION_DATE` | **Missing**: fill in when signing, e.g. `15 de noviembre de 2026` |
| Signed | `VERIFACTU_DECLARATION_SIGNED=true` | Only after review and signature |

Questions for the adviser:

1. Is the list of contents complete for Orden HAC/1177/2024 (art. 15)? We included: system name, identifier,
   version, components and functions, VERI*FACTU-only, use by several taxpayers, type of signature (not
   applicable), producer's name, NIF and address, statement of compliance, place and date.
2. Is the description of components detailed enough for a cloud (SaaS) system?
3. Where else should it be published (for example, Brasatech's website)?
4. Does a new declaration need to be signed for every release, or only for versions that change the
   billing behaviour? (FastBite's version is configurable for this reason.)

---

## Texto

**DECLARACIÓN RESPONSABLE DEL SISTEMA INFORMÁTICO DE FACTURACIÓN**

**[Razón social del productor]**, con NIF **[NIF]** y domicilio en **[dirección postal completa]**, en su
condición de productora del sistema informático de facturación que se identifica a continuación,
**DECLARA BAJO SU RESPONSABILIDAD:**

### 1. Identificación del sistema informático

| | |
|---|---|
| Nombre del sistema informático | FastBite |
| Código identificador del sistema | FB |
| Identificador completo de la versión | [versión, p. ej. 1.0] |
| Productor | [razón social] |
| NIF del productor | [NIF] |
| Dirección de contacto del productor | [dirección postal completa] |
| Funciona exclusivamente como VERI*FACTU | Sí |
| Permite su uso por varios obligados tributarios | Sí |
| Tipo de firma de los registros | No aplica: los registros se remiten a la Agencia Tributaria (modalidad VERI*FACTU) |

### 2. Componentes y funcionalidades

FastBite es una aplicación web que el productor presta como servicio desde sus servidores en la nube, con
una base de datos relacional. Se utiliza a través de un navegador web en ordenadores, tabletas o teléfonos, y
los tickets se imprimen en las impresoras conectadas a esos dispositivos. No requiere instalar software en
los equipos del usuario.

Sus funcionalidades de facturación son:

- Emisión de facturas simplificadas, facturas completas, facturas emitidas en sustitución de facturas
  simplificadas y facturas rectificativas, cada tipo en su propia serie y con numeración correlativa sin saltos.
- Generación, en el mismo momento de la expedición de cada factura, de su registro de facturación de alta,
  encadenado al registro anterior mediante la huella calculada con el algoritmo SHA-256.
- Impresión del código QR tributario y de la leyenda «VERI*FACTU» en cada factura.
- Remisión de los registros de facturación a la sede electrónica de la Agencia Estatal de Administración
  Tributaria.
- Conservación de las facturas y registros, que no pueden modificarse ni eliminarse: las correcciones se
  realizan siempre mediante facturas rectificativas.
- Consulta y descarga de todos los registros de facturación por el obligado tributario.
- Separación completa de los datos de cada obligado tributario: cada local tiene su propia base de datos,
  sus propias series y su propia cadena de registros.

### 3. Declaración de cumplimiento

Que el sistema informático de facturación identificado en esta declaración, en la versión indicada, cumple
lo dispuesto en el artículo 29.2.j) de la Ley 58/2003, de 17 de diciembre, General Tributaria; en el
Reglamento que establece los requisitos que deben adoptar los sistemas y programas informáticos o
electrónicos que soporten los procesos de facturación de empresarios y profesionales, y la estandarización
de formatos de los registros de facturación, aprobado por el Real Decreto 1007/2023, de 5 de diciembre; en
la Orden HAC/1177/2024, de 17 de octubre; y en la demás normativa que los desarrolla.

Que, en particular, garantiza la integridad, conservación, accesibilidad, legibilidad, trazabilidad e
inalterabilidad de los registros de facturación.

En **[lugar]**, a **[fecha de firma]**.

Firmado: **[razón social del productor]**

---

## Before signing, make sure the software matches the text

The declaration states facts about FastBite. Check them against the software before signing:

- **"Remisión de los registros … a la Agencia Tributaria"**: done. FastBite connects directly to the AEAT with
  Brasatech's certificate; records were accepted by the AEAT test environment (12/10/2026), and both servers load
  the certificate since 27/10/2026. Production sending stays off (`VERIFACTU_PRODUCTION_ENABLED=false`) until
  go-live, and a restaurant's records are sent only once it has authorised Brasatech (`VERIFACTU_ISSUERS`).
- **Refund codes**: refunds of full invoices are reported as R1; confirm with the adviser whether some should
  be R4 (corrections of mistakes).

-- Record which owner revoked a VERI*FACTU authorisation.
ALTER TABLE public.verifactu_authorizations ADD COLUMN revoked_by VARCHAR(160);

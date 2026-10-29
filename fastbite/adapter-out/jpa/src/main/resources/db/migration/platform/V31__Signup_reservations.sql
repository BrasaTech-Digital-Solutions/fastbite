-- Serialize reservation and verification updates across application instances.
CREATE TABLE public.signup_reservation_lock (
    id INTEGER PRIMARY KEY
);
INSERT INTO public.signup_reservation_lock (id) VALUES (1);

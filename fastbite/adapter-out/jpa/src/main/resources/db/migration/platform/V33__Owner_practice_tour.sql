ALTER TABLE public.owner_onboarding ADD COLUMN practice_guest_viewed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.owner_onboarding ADD COLUMN practice_table_ordered BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.owner_onboarding ADD COLUMN practice_kitchen_prepared BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.owner_onboarding ADD COLUMN practice_counter_charged BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.owner_onboarding ADD COLUMN practice_menu_personalized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.owner_onboarding ADD COLUMN practice_team_tested BOOLEAN NOT NULL DEFAULT FALSE;

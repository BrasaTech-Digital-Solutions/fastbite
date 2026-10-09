-- Allergens of a dish as codes separated by commas (GLUTEN,MILK). NULL: not declared yet; '': none of the 14.
ALTER TABLE products ADD COLUMN IF NOT EXISTS allergens VARCHAR(200);

-- Allergens an option adds to the dish, such as MILK for extra cheese. NULL or '': none.
ALTER TABLE customization_options ADD COLUMN IF NOT EXISTS allergens VARCHAR(200);

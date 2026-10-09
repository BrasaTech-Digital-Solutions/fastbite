# Allergens

Restaurants in the EU must tell guests which of the 14 allergens of Regulation (EU) 1169/2011 each dish
contains (in Spain also Real Decreto 126/2015). FastBite keeps that information per dish and per option and
shows it wherever a guest or a member of staff chooses food.

## Declaring allergens (back office)

- **Products → edit a dish → Allergens**: tick every allergen the dish contains, or tick *This dish contains
  none of the 14 allergens*. Leaving both empty means "not declared yet".
- **Customizations → option → Adds allergens**: options can add allergens to a dish, such as milk for extra
  cheese. Options can only add, never remove: a dish that contains gluten keeps its gluten even if a
  gluten-free bread option exists, so the information shown is always on the safe side.
- The products list warns about dishes on sale whose allergens are not declared, and marks each one.

"Not declared" and "none" are different on purpose: an undeclared dish never looks safe to a guest.

## What guests and staff see

| Where | What |
|---|---|
| Guest menu | Each dish shows its allergens (icon and name), "No allergens", or "Ask our staff about allergens". |
| Guest menu → *Allergies?* | Guests choose what they cannot eat; dishes containing it are hidden. Undeclared dishes stay visible and their warning is highlighted. The choice is remembered on that device only. |
| Customize dialog | Options show the allergens they add. |
| Allergen chart (`/{location}/allergens`) | Every dish on sale against the 14 allergens, plus the options that add allergens. Public, printable on one landscape page, linked from the menu and the back office. |
| Counter | Product tiles show the allergen icons (names on hover) or "Allergens not declared". |

## Storage

`products.allergens` and `customization_options.allergens` hold codes separated by commas
(`GLUTEN,MILK`); `NULL` means not declared and `''` means none. Backups and demo templates carry them.
The demo restaurants declare allergens for every dish so a new sandbox shows the feature straight away.

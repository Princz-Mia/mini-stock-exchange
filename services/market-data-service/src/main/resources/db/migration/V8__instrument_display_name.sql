-- The display name an instrument's snapshot carries, null for none. Execution checks the name's rules; this
-- projection only mirrors what it published, so a rebuild gives the same rows.
ALTER TABLE instrument_projection ADD COLUMN display_name TEXT;

-- The tape's pages filter by instrument and go newest first by trade sequence, which a backward scan of the unique
-- index on (instrument_id, trade_sequence) serves; this index duplicated it.
DROP INDEX public_trade_tape;

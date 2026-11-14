-- A bot's strategy: the kind of trader it plays in a run. It is set when the bot is created and never changed, so the
-- runtime role gets no UPDATE on it. Only bots have one; a bot made without one is untagged.
ALTER TABLE account
    ADD COLUMN bot_strategy TEXT CHECK (bot_strategy IN ('MARKET_MAKER', 'NOISE', 'MOMENTUM')),
    ADD CONSTRAINT account_bot_strategy_only_for_bots CHECK (kind = 'BOT' OR bot_strategy IS NULL);

-- Bots made before tags existed are tagged by creation order, oldest first, as runs have picked them: the first fifth
-- market makers, the last fifth momentum, the rest noise. That is the operator screen's suggested split, 4, 12 and 4
-- for the demo's 20 bots; fewer than five bots are all noise.
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY simulation_id ORDER BY created_at, id) AS position,
           count(*) OVER (PARTITION BY simulation_id) AS population
    FROM account
    WHERE kind = 'BOT')
UPDATE account
SET bot_strategy = CASE
        WHEN ranked.position <= ranked.population / 5 THEN 'MARKET_MAKER'
        WHEN ranked.position > ranked.population - ranked.population / 5 THEN 'MOMENTUM'
        ELSE 'NOISE'
    END
FROM ranked
WHERE account.id = ranked.id;

-- The bot population has no limit, so the writer reads bots through an index rather than the whole account table:
-- provisioning counts a market's bots, a run counts each strategy's and assigns the oldest of each, in creation order.
CREATE INDEX account_bots_by_strategy ON account (simulation_id, bot_strategy, created_at, id) WHERE kind = 'BOT';

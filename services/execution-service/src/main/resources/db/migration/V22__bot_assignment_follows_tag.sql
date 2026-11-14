-- A run assigns each strategy to bots tagged with it. The database refuses an assignment that disagrees with the bot's
-- tag, or names an untagged bot: (account_id, strategy) must match a bot's (id, bot_strategy). Runs started before
-- runs assigned by tag picked bots by creation order, so their assignments are not checked (NOT VALID); every new one
-- is. The unique constraint is what the foreign key needs to reference; id alone is already unique.
ALTER TABLE account ADD CONSTRAINT account_id_bot_strategy_key UNIQUE (id, bot_strategy);

ALTER TABLE bot_assignment
    ADD CONSTRAINT bot_assignment_follows_tag FOREIGN KEY (account_id, strategy)
        REFERENCES account (id, bot_strategy) NOT VALID;

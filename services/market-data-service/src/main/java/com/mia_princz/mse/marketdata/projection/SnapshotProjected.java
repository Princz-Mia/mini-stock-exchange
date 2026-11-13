package com.mia_princz.mse.marketdata.projection;

import java.util.UUID;

/**
 * Published inside the transaction that changed what an instrument's live frame shows: its market row, or its session
 * figures after a new trade. Listeners that act on it outside the database, such as the live stream, listen after
 * commit, so a rolled-back change is never shown.
 */
public record SnapshotProjected(UUID instrumentId) {
}

package com.mia_princz.mse.marketdata.projection;

import java.util.UUID;

/**
 * Published inside the transaction that replaced an instrument's market row. Listeners that act on it outside the
 * database, such as the live stream, listen after commit, so a rolled-back change is never shown.
 */
public record SnapshotProjected(UUID instrumentId) {
}

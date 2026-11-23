package com.mia_princz.mse.execution.trading.application;

/**
 * What one later part of a close's order sweep did, stored under its own command sequence as every command's answer
 * is: how many resting orders it cancelled, and how many still rested after it. Nobody asks for it by key; it is the
 * record of that sequence.
 */
public record MarketSweep(int canceledOrders, int restingOrders, long commandSequence) {
}

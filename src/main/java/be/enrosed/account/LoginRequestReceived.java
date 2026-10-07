package be.enrosed.account;

/**
 * A login request staff should hear about was stored. changed means the notice is for an
 * entry appended to a request that was already open.
 */
public record LoginRequestReceived(long id, boolean changed) {}
